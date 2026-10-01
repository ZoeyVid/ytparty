package de.zoeyvid.ytparty.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class VideoPlayer {
    private static final int FPS = 30;

    public interface Sink { void accept(byte[] pixels, int width, int height); }

    private final LongSupplier position;
    private final Runnable onFailure;
    private volatile Session session;
    private String failedUrl;

    public VideoPlayer(LongSupplier position, Runnable onFailure) {
        this.position = position;
        this.onFailure = onFailure;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void frame(String url, String headers, String cookies, boolean hls, boolean live, int seeks, int width, int height, Sink sink) throws IOException {
        if (session != null && session.failed && !session.url.equals(failedUrl)) { failedUrl = session.url; onFailure.run(); }
        long restart = session != null && session.url.equals(url) && session.seeks == seeks ? session.restart : 0;
        if (session != null && (restart > 0 || session.closed || session.failed && System.nanoTime() - session.started > 5_000_000_000L || !session.url.equals(url) || session.seeks != seeks || (session.width != width || session.height != height) && System.nanoTime() - session.started > 500_000_000L)) stop();
        if (session == null && url != null) session = new Session(url, headers, cookies, hls, live, seeks, width, height, position.getAsLong() + restart, restart);
        if (session == null) return;
        session.polled = System.nanoTime();
        byte[] pixels = session.latest.getAndSet(null);
        if (pixels != null) { sink.accept(pixels, session.width, session.height); session.free.offer(pixels); }
    }

    public void stop() {
        if (session == null) return;
        session.closed = true;
        session.process.destroyForcibly();
        session = null;
    }

    private final class Session implements Runnable {
        private final String url;
        private final boolean live;
        private final int seeks, width, height;
        private final long start, ahead;
        private final Process process;
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private final Queue<byte[]> free = new ConcurrentLinkedQueue<>();
        private final long started = System.nanoTime();
        private volatile boolean closed, failed;
        private volatile long polled = System.nanoTime(), restart;

        Session(String url, String headers, String cookies, boolean hls, boolean live, int seeks, int width, int height, long start, long ahead) throws IOException {
            this.url = url;
            this.live = live;
            this.seeks = seeks;
            this.width = width;
            this.height = height;
            this.start = start;
            this.ahead = ahead;
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-protocol_whitelist", FfmpegAudioTrack.PROTOCOLS, "-reconnect", "1", "-rw_timeout", "10000000", "-headers", headers, "-cookies", cookies, "-hwaccel", "auto"));
            if (hls) command.addAll(List.of("-http_seekable", "0"));
            if (start > 0 && !live) command.addAll(List.of("-ss", start + "ms", "-copyts", "-start_at_zero"));
            command.addAll(List.of("-i", url, "-an", "-sn", "-dn", "-vf", "fps=" + FPS + ",scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,pad=" + width + ":" + height + ":-1:-1",
                "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1"));
            process = Tools.start(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD));
            Thread reader = new Thread(this, "ytparty-video");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void run() {
            try (InputStream in = process.getInputStream()) {
                boolean shown = false;
                long latency = 0, first = 0;
                for (long n = 0; ; n++) {
                    byte[] pixels = Objects.requireNonNullElseGet(free.poll(), () -> new byte[width * height * 4]);
                    if (in.readNBytes(pixels, 0, pixels.length) < pixels.length) break;
                    if (n == 0) { latency = (System.nanoTime() - started) / 1_000_000; first = position.getAsLong(); }
                    while (!closed && System.nanoTime() - polled <= 1_000_000_000L && position.getAsLong() < start + n * 1000 / FPS) Thread.sleep(10);
                    if (closed || System.nanoTime() - polled > 1_000_000_000L) { closed = true; process.destroyForcibly(); return; }
                    long late = position.getAsLong() - start - (n + 1) * 1000 / FPS;
                    if (!shown && late > 0) {
                        long dropping = position.getAsLong() - first, gained = n * 1000 / FPS - dropping, lead = Math.min(latency + 500, 10_000);
                        if (dropping < Math.max(1000, ahead) || late * dropping <= lead * gained || lead <= ahead && gained > 0) { free.offer(pixels); continue; }
                        if (lead > ahead && !live) { restart = lead; process.destroyForcibly(); return; }
                    }
                    shown = true;
                    byte[] skipped = latest.getAndSet(pixels);
                    if (skipped != null) free.offer(skipped);
                }
                failed = process.waitFor() != 0;
            } catch (IOException | InterruptedException ignored) {}
        }
    }
}
