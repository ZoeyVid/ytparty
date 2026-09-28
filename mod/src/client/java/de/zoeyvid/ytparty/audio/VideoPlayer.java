package de.zoeyvid.ytparty.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class VideoPlayer {
    public static final int WIDTH = 320, HEIGHT = 180;
    private static final int FPS = 20, FRAME = WIDTH * HEIGHT * 4;

    private final LongSupplier position;
    private Session session;

    public VideoPlayer(LongSupplier position) { this.position = position; }

    public byte[] frame(String url, int seeks) throws IOException {
        if (session != null && (!session.url.equals(url) || session.seeks != seeks)) stop();
        if (session == null && url != null) session = new Session(url, seeks, position.getAsLong());
        return session == null ? null : session.latest.getAndSet(null);
    }

    public void stop() {
        if (session == null) return;
        session.closed = true;
        session.process.destroy();
        session = null;
    }

    private final class Session implements Runnable {
        private final String url;
        private final int seeks;
        private final long start;
        private final Process process;
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private volatile boolean closed;

        Session(String url, int seeks, long start) throws IOException {
            this.url = url;
            this.seeks = seeks;
            this.start = start;
            process = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-reconnect", "1", "-ss", start + "ms", "-i", url, "-an", "-sn", "-dn",
                "-vf", "fps=" + FPS + ",scale=" + WIDTH + ":" + HEIGHT + ":force_original_aspect_ratio=decrease,pad=" + WIDTH + ":" + HEIGHT + ":-1:-1",
                "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Thread reader = new Thread(this, "ytparty-video");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void run() {
            try (InputStream in = process.getInputStream()) {
                for (long n = 0; ; n++) {
                    byte[] pixels = in.readNBytes(FRAME);
                    if (pixels.length < FRAME) return;
                    while (!closed && position.getAsLong() < start + n * 1000 / FPS) Thread.sleep(10);
                    if (closed) return;
                    latest.set(pixels);
                }
            } catch (IOException | InterruptedException ignored) {}
        }
    }
}
