package de.zoeyvid.ytparty.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MusicPlayer {
    private static final AudioDataFormat FORMAT = StandardAudioDataFormats.COMMON_PCM_S16_LE;

    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player = manager.createPlayer();
    private final OpenAlOutput out = new OpenAlOutput(FORMAT.sampleRate, FORMAT.maximumChunkSize());
    private volatile boolean running = true;
    private Runnable onEnd = () -> {};
    private Runnable onError = () -> {};
    private AudioTrack lastTrack;
    private volatile boolean decodeFinished;
    private volatile long seekTarget = -1;

    private static final ExecutorService RESOLVER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ytparty-resolve"); t.setDaemon(true); return t; });
    private static boolean newPipeReady;
    private static final Map<String, StreamInfo> RESOLVED = new HashMap<>();
    private String playing;
    private boolean mayRetry;

    public MusicPlayer() {
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.setFrameBufferDuration(1000);
        manager.registerSourceManager(new HttpAudioSourceManager());
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                if (reason == AudioTrackEndReason.FINISHED) decodeFinished = true;
                else if (reason == AudioTrackEndReason.LOAD_FAILED) failed();
            }
        });
        Thread pump = new Thread(this::pumpLoop, "ytparty-audio");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY);
        pump.start();
    }

    private static synchronized StreamInfo streamInfo(String identifier) throws Exception {
        if (!newPipeReady) { NewPipe.init(new NewPipeDownloader()); newPipeReady = true; }
        StreamInfo cached = RESOLVED.get(identifier);
        if (cached != null) return cached;
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, identifier);
        RESOLVED.put(identifier, info);
        return info;
    }

    private static synchronized void forget(String identifier) { RESOLVED.remove(identifier); }

    private static String bestAudioUrl(StreamInfo info) {
        AudioStream best = null;
        for (AudioStream stream : info.getAudioStreams()) {
            if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || stream.getContent() == null || stream.getContent().isBlank()) continue;
            if (best == null || stream.getAverageBitrate() > best.getAverageBitrate()) best = stream;
        }
        return best == null ? null : best.getContent();
    }

    private static boolean live(StreamInfo info) {
        return info.getStreamType() == StreamType.LIVE_STREAM || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
    }

    public void setOnEnd(Runnable r) { onEnd = r != null ? r : () -> {}; }
    public void setOnError(Runnable r) { onError = r != null ? r : () -> {}; }

    public void resolveAll(String identifier, Consumer<List<String[]>> onDone) {
        RESOLVER.execute(() -> {
            try {
                StreamInfo info = streamInfo(identifier);
                onDone.accept(live(info) ? List.of() : List.<String[]>of(new String[]{identifier, info.getName()}));
            } catch (Exception e) { onDone.accept(List.of()); }
        });
    }

    public void resolve(String identifier, BiConsumer<String, String> onResolved, Runnable onFail) {
        RESOLVER.execute(() -> {
            try {
                StreamInfo info = streamInfo(identifier);
                if (live(info)) onFail.run(); else onResolved.accept(identifier, info.getName());
            } catch (Exception e) { onFail.run(); }
        });
    }

    public void playIdentifier(String identifier, Consumer<String> onTitle) {
        playing = identifier;
        mayRetry = true;
        RESOLVER.execute(() -> load(identifier, onTitle));
    }

    private void load(String identifier, Consumer<String> onTitle) {
        StreamInfo info;
        String url;
        try { info = streamInfo(identifier); url = live(info) ? null : bestAudioUrl(info); } catch (Exception e) { failed(); return; }
        if (url == null) { failed(); return; }
        manager.loadItem(url, new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { start(track, info.getName(), onTitle); }
            public void playlistLoaded(AudioPlaylist list) { if (list.getTracks().isEmpty()) failed(); else start(pick(list), info.getName(), onTitle); }
            public void noMatches() { failed(); }
            public void loadFailed(FriendlyException e) { failed(); }
        });
    }

    private void failed() {
        if (playing == null || !mayRetry) { onError.run(); return; }
        mayRetry = false;
        String identifier = playing;
        forget(identifier);
        RESOLVER.execute(() -> load(identifier, null));
    }

    private void begin(AudioTrack track) {
        decodeFinished = false;
        seekTarget = -1;
        out.requestFlush();
        player.playTrack(track);
    }

    private void start(AudioTrack track, String title, Consumer<String> onTitle) {
        lastTrack = track;
        begin(track);
        if (onTitle != null) onTitle.accept(title != null ? title : track.getInfo().title);
    }

    public void repeatCurrent() { mayRetry = true; if (lastTrack != null) begin(lastTrack.makeClone()); }

    private static AudioTrack pick(AudioPlaylist list) {
        return list.getSelectedTrack() != null ? list.getSelectedTrack() : list.getTracks().getFirst();
    }

    public void seekBy(long deltaMs) {
        AudioTrack t = player.getPlayingTrack();
        if (t != null && t.isSeekable()) setPosition(position() + deltaMs);
    }

    public void setPosition(long ms) {
        AudioTrack t = player.getPlayingTrack();
        if (t != null && t.isSeekable()) { long p = Math.max(0, Math.min(t.getDuration() - 1, ms)); t.setPosition(p); seekTarget = p; out.requestFlush(); }
    }

    public long position() { AudioTrack t = player.getPlayingTrack(); return t != null ? Math.max(0, t.getPosition() - out.bufferedAhead()) : 0; }
    public long duration() { AudioTrack t = player.getPlayingTrack(); return t != null ? t.getDuration() : 0; }

    public void setPaused(boolean paused) { player.setPaused(paused); out.requestPause(paused); }
    public boolean isPaused() { return player.isPaused(); }
    public boolean seeking() { return seekTarget >= 0; }
    public void stop() { decodeFinished = false; player.stopTrack(); out.requestFlush(); }
    public void setVolume(int v) { out.setGain(Math.clamp(v, 0, 200) / 100f); }

    public void close() { running = false; }

    private void pumpLoop() {
        MutableAudioFrame frame = new MutableAudioFrame();
        byte[] buf = new byte[FORMAT.maximumChunkSize()];
        frame.setBuffer(ByteBuffer.wrap(buf));
        while (running) {
            boolean has = player.provide(frame);
            boolean stale = has && seekTarget >= 0 && Math.abs(frame.getTimecode() - seekTarget) > 60;
            if (has && seekTarget >= 0 && !stale) seekTarget = -1;
            out.pump(buf, has && !stale ? frame.getDataLength() : 0, has && !stale);
            if (decodeFinished && out.bufferedAhead() == 0) { decodeFinished = false; onEnd.run(); }
            if (!has) sleep();
        }
        out.shutdown();
    }

    private static void sleep() { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
}
