package de.zoeyvid.ytparty.audio;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class FfmpegAudioTrack extends BaseAudioTrack {
    static final String PROTOCOLS = "http,https,tcp,tls,crypto,httpproxy,data";

    private final String headers;
    private final boolean hls;
    private volatile long start;
    volatile boolean failed;

    FfmpegAudioTrack(AudioTrackInfo info, String headers, boolean hls) {
        super(info);
        this.headers = headers;
        this.hls = hls;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        AudioDataFormat format = executor.getProcessingContext().outputFormat;
        AudioPipeline pipeline = AudioPipelineFactory.create(executor.getProcessingContext(), new PcmFormat(format.channelCount, format.sampleRate));
        try {
            executor.executeProcessingLoop(() -> {
                if (!decode(pipeline, format, true) && start < trackInfo.length - 1000) {
                    if (start == 0) throw new IOException("ffmpeg returned no audio");
                    if (hls) decode(pipeline, format, false);
                }
                while (executor.getAudioBuffer().getLastInputTimecode() != null) Thread.sleep(10);
            }, position -> { start = position; pipeline.seekPerformed(position, position); });
        }
        catch (RuntimeException e) { failed = true; throw e; }
        finally { pipeline.close(); }
    }

    @Override
    public boolean isSeekable() { return trackInfo.length != Units.DURATION_MS_UNKNOWN; }

    private boolean decode(AudioPipeline pipeline, AudioDataFormat format, boolean inputSeek) throws Exception {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-protocol_whitelist", PROTOCOLS, "-reconnect", "1", "-reconnect_streamed", trackInfo.isStream ? "1" : "0", "-reconnect_on_network_error", "1", "-reconnect_delay_max", "5", "-rw_timeout", "3000000", "-headers", headers));
        if (start > 0 && inputSeek) command.addAll(List.of("-ss", start + "ms", "-copyts", "-start_at_zero"));
        command.addAll(List.of("-i", trackInfo.uri));
        if (start > 0 && !inputSeek) command.addAll(List.of("-ss", start + "ms"));
        command.addAll(List.of("-vn", "-sn", "-dn", "-f", "s16le", "-ac", Integer.toString(format.channelCount), "-ar", Integer.toString(format.sampleRate), "pipe:1"));
        Process process = Tools.start(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD));
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[format.totalSampleCount() * 2];
            boolean received = false;
            while (true) {
                boolean alive = process.isAlive();
                int length = Math.min(in.available(), buffer.length) & ~3;
                if (length == 0 && !alive) break;
                if (length < buffer.length && alive) { Thread.sleep(5); continue; }
                in.readNBytes(buffer, 0, length);
                pipeline.process(ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer());
                received = true;
            }
            if (process.waitFor() != 0) throw new IOException("ffmpeg exited with " + process.exitValue());
            return received;
        } finally { process.destroyForcibly(); }
    }

    @Override
    protected AudioTrack makeShallowClone() { return new FfmpegAudioTrack(trackInfo, headers, hls); }
}
