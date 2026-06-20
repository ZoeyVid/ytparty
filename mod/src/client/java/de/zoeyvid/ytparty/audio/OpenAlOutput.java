package de.zoeyvid.ytparty.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.alcGetContextsDevice;
import static org.lwjgl.openal.ALC10.alcGetCurrentContext;

final class OpenAlOutput {
    private final int sampleRate;
    private final ByteBuffer pcm;
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private long context;
    private int source;
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

    void pump(byte[] data, int len, boolean hasFrame) {
        if (!ensure()) return;
        if (wantGain != appliedGain) { alSourcef(source, AL_GAIN, wantGain); appliedGain = wantGain; }
        if (wantFlush) { wantFlush = false; alSourceStop(source); reclaim(); }
        if (wantPaused != paused) {
            paused = wantPaused;
            if (paused) alSourcePause(source);
            else if (alGetSourcei(source, AL_BUFFERS_QUEUED) > 0) alSourcePlay(source);
        }
        if (!hasFrame || paused) return;
        reclaim();
        int buf = takeBuffer();
        if (buf == 0) return;
        pcm.clear();
        pcm.put(data, 0, len).flip();
        alBufferData(buf, AL_FORMAT_STEREO16, pcm, sampleRate);
        alSourceQueueBuffers(source, buf);
        if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) alSourcePlay(source);
    }

    void shutdown() {
        if (source != 0) { alSourceStop(source); reclaim(); alDeleteSources(source); source = 0; }
        while (!free.isEmpty()) alDeleteBuffers(free.poll());
        MemoryUtil.memFree(pcm);
    }

    private boolean ensure() {
        long ctx = alcGetCurrentContext();
        if (ctx == 0L) return false;
        if (ctx != context || source == 0 || !alIsSource(source)) {
            ALCCapabilities alcCaps = ALC.createCapabilities(alcGetContextsDevice(ctx));
            AL.setCurrentThread(AL.createCapabilities(alcCaps));
            context = ctx;
            source = alGenSources();
            free.clear();
            for (int i = 0; i < 16; i++) free.add(alGenBuffers());
            paused = false;
            wantPaused = false;
            appliedGain = -1f;
        }
        return true;
    }

    private void reclaim() {
        int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) free.add(alSourceUnqueueBuffers(source));
    }

    private int takeBuffer() {
        for (int spin = 0; spin < 5 && free.isEmpty(); spin++) {
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            reclaim();
        }
        return free.isEmpty() ? 0 : free.poll();
    }
}
