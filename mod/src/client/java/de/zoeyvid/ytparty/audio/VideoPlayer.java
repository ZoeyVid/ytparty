package de.zoeyvid.ytparty.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class VideoPlayer {
    private static final int FPS = 30;

    private final LongSupplier position;
    private Session session;

    public VideoPlayer(LongSupplier position) { this.position = position; }

    public void frame(String url, int seeks, int width, int height, Consumer<byte[]> sink) throws IOException {
        if (session != null && (session.closed || !session.url.equals(url) || session.seeks != seeks || session.width != width || session.height != height)) stop();
        if (session == null && url != null) session = new Session(url, seeks, width, height, position.getAsLong());
        if (session == null) return;
        session.polled = System.nanoTime();
        byte[] pixels = session.latest.getAndSet(null);
        if (pixels != null) { sink.accept(pixels); session.free.offer(pixels); }
    }

    public void stop() {
        if (session == null) return;
        session.closed = true;
        session.process.destroy();
        session = null;
    }

    private final class Session implements Runnable {
        private final String url;
        private final int seeks, width, height;
        private final long start;
        private final Process process;
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private final Queue<byte[]> free = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;
        private volatile long polled = System.nanoTime();

        Session(String url, int seeks, int width, int height, long start) throws IOException {
            this.url = url;
            this.seeks = seeks;
            this.width = width;
            this.height = height;
            this.start = start;
            process = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-reconnect", "1", "-hwaccel", "auto", "-ss", start + "ms", "-i", url, "-an", "-sn", "-dn",
                "-vf", "fps=" + FPS + ",scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,pad=" + width + ":" + height + ":-1:-1",
                "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Thread reader = new Thread(this, "ytparty-video");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void run() {
            try (InputStream in = process.getInputStream()) {
                for (long n = 0; ; n++) {
                    byte[] pixels = Objects.requireNonNullElseGet(free.poll(), () -> new byte[width * height * 4]);
                    if (in.readNBytes(pixels, 0, pixels.length) < pixels.length) return;
                    while (!closed && position.getAsLong() < start + n * 1000 / FPS) Thread.sleep(10);
                    if (closed || System.nanoTime() - polled > 1_000_000_000L) { closed = true; process.destroy(); return; }
                    byte[] skipped = latest.getAndSet(pixels);
                    if (skipped != null) free.offer(skipped);
                }
            } catch (IOException | InterruptedException ignored) {}
        }
    }
}
