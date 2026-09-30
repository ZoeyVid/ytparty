package de.zoeyvid.ytparty.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.alcGetCurrentContext;

final class OpenAlOutput {
    private static final int BUFFERS = 64;
    private final int sampleRate;
    private final ByteBuffer pcm;
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final LinkedHashMap<Integer, byte[]> queued = new LinkedHashMap<>();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private volatile int aheadMs;
    private ALCapabilities caps;
    private int source;
    private long elapsed, last;
    private boolean paused;
    private volatile boolean wantPaused;
    private volatile boolean wantFlush;
    private volatile float wantGain = 1f;
    private float appliedGain = -1f;

    OpenAlOutput(int sampleRate, int maxChunk) {
        this.sampleRate = sampleRate;
        this.pcm = MemoryUtil.memAlloc(maxChunk);
    }

    void requestPause(boolean p) { wantPaused = p; }
    void requestFlush() { wantFlush = true; }
    void setGain(float g) { wantGain = g; }
    int bufferedAhead() { return aheadMs; }

    boolean ready() {
        if (!ensure()) return idle();
        if (wantGain != appliedGain) { alSourcef(source, AL_GAIN, wantGain); appliedGain = wantGain; }
        if (wantFlush) { wantFlush = false; alSourceStop(source); reclaim(); }
        if (wantPaused != paused) {
            paused = wantPaused;
            if (paused) alSourcePause(source);
            else if (alGetSourcei(source, AL_BUFFERS_QUEUED) > 0) alSourcePlay(source);
        }
        reclaim();
        int bytes = 0;
        for (byte[] d : queued.values()) bytes += d.length;
        aheadMs = (int) ((long) bytes * 1000 / ((long) sampleRate * 4));
        return !paused && !free.isEmpty();
    }

    void write(byte[] data, int len) {
        byte[] copy = Arrays.copyOf(data, len);
        if (source == 0) pending.add(copy); else queue(copy);
    }

    void shutdown() {
        if (ensure()) { alSourceStop(source); reclaim(); alDeleteSources(source); while (!free.isEmpty()) alDeleteBuffers(free.poll()); }
        MemoryUtil.memFree(pcm);
    }

    private boolean ensure() {
        if (alcGetCurrentContext() == 0L) { lost(); return false; }
        ALCapabilities current;
        try { current = AL.getCapabilities(); } catch (IllegalStateException e) { return false; }
        if (current != caps) {
            lost();
            if ((source = alGenSources()) == 0) return false;
            caps = current;
            for (int i = 0; i < BUFFERS; i++) free.add(alGenBuffers());
            alSourcef(source, AL_GAIN, appliedGain = wantGain);
            while (!pending.isEmpty()) queue(pending.poll());
        } else if (!alIsSource(source)) lost();
        return source != 0;
    }

    private void lost() {
        if (source == 0) return;
        source = 0;
        pending.addAll(queued.values());
        queued.clear();
        free.clear();
        elapsed = 0;
        last = System.nanoTime();
    }

    private boolean idle() {
        long now = System.nanoTime();
        if (wantFlush) { wantFlush = false; pending.clear(); }
        paused = wantPaused;
        if (!paused) elapsed += now - last;
        last = now;
        while (!pending.isEmpty() && elapsed >= nanos(pending.peek().length)) elapsed -= nanos(pending.poll().length);
        if (pending.isEmpty()) elapsed = 0;
        long bytes = 0;
        for (byte[] d : pending) bytes += d.length;
        aheadMs = (int) ((nanos(bytes) - elapsed) / 1_000_000);
        return !paused && pending.size() < BUFFERS;
    }

    private long nanos(long bytes) { return bytes * 1_000_000_000L / (sampleRate * 4L); }

    private void queue(byte[] data) {
        int buf = free.poll();
        pcm.clear();
        pcm.put(data).flip();
        alBufferData(buf, AL_FORMAT_STEREO16, pcm, sampleRate);
        queued.put(buf, data);
        alSourceQueueBuffers(source, buf);
        if (!paused && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) alSourcePlay(source);
    }

    private void reclaim() {
        int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) { int b = alSourceUnqueueBuffers(source); queued.remove(b); free.add(b); }
    }
}
