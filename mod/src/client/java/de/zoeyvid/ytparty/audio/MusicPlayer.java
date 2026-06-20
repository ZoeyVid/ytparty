package de.zoeyvid.ytparty.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MusicPlayer {
    private static final AudioDataFormat FORMAT = StandardAudioDataFormats.COMMON_PCM_S16_LE;

    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player = manager.createPlayer();
    private final OpenAlOutput out = new OpenAlOutput(FORMAT.sampleRate, FORMAT.maximumChunkSize());
    private volatile boolean running = true;
    private Runnable onEnd = () -> {};
    private AudioTrack lastTrack;

    public MusicPlayer() {
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.registerSourceManager(new YoutubeAudioSourceManager());
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                if (reason.mayStartNext) onEnd.run();
            }
        });
        Thread pump = new Thread(this::pumpLoop, "ytparty-audio");
        pump.setDaemon(true);
        pump.start();
    }

    public void setOnEnd(Runnable r) { onEnd = r != null ? r : () -> {}; }

    public void resolveAll(String identifier, Consumer<List<String[]>> onDone) {
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { onDone.accept(List.<String[]>of(new String[]{identifier, track.getInfo().title})); }
            public void playlistLoaded(AudioPlaylist list) {
                List<String[]> tracks = new ArrayList<>();
                for (AudioTrack t : list.getTracks()) tracks.add(new String[]{t.getInfo().uri, t.getInfo().title});
                onDone.accept(tracks);
            }
            public void noMatches() { onDone.accept(List.of()); }
            public void loadFailed(FriendlyException e) { onDone.accept(List.of()); }
        });
    }

    public void resolve(String identifier, BiConsumer<String, String> onResolved, Runnable onFail) {
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { onResolved.accept(identifier, track.getInfo().title); }
            public void playlistLoaded(AudioPlaylist list) {
                if (list.getTracks().isEmpty()) { onFail.run(); return; }
                AudioTrack first = list.getTracks().getFirst();
                onResolved.accept(first.getInfo().uri, first.getInfo().title);
            }
            public void noMatches() { onFail.run(); }
            public void loadFailed(FriendlyException e) { onFail.run(); }
        });
    }

    public void playIdentifier(String identifier, Consumer<String> onTitle) {
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { start(track, onTitle); }
            public void playlistLoaded(AudioPlaylist list) { if (list.getTracks().isEmpty()) onEnd.run(); else start(pick(list), onTitle); }
            public void noMatches() { onEnd.run(); }
            public void loadFailed(FriendlyException e) { onEnd.run(); }
        });
    }

    private void start(AudioTrack track, Consumer<String> onTitle) {
        lastTrack = track;
        out.requestFlush();
        player.playTrack(track);
        if (onTitle != null) onTitle.accept(track.getInfo().title);
    }

    public void repeatCurrent() { if (lastTrack != null) { out.requestFlush(); player.playTrack(lastTrack.makeClone()); } }

    private static AudioTrack pick(AudioPlaylist list) {
        return list.getSelectedTrack() != null ? list.getSelectedTrack() : list.getTracks().getFirst();
    }

    public void seekBy(long deltaMs) {
        AudioTrack t = player.getPlayingTrack();
        if (t != null && t.isSeekable()) setPosition(t.getPosition() + deltaMs);
    }

    public void setPosition(long ms) {
        AudioTrack t = player.getPlayingTrack();
        if (t != null && t.isSeekable()) { t.setPosition(Math.max(0, Math.min(t.getDuration() - 1, ms))); out.requestFlush(); }
    }

    public long position() { AudioTrack t = player.getPlayingTrack(); return t != null ? t.getPosition() : 0; }
    public long duration() { AudioTrack t = player.getPlayingTrack(); return t != null ? t.getDuration() : 0; }

    public void setPaused(boolean paused) { player.setPaused(paused); out.requestPause(paused); }
    public boolean isPaused() { return player.isPaused(); }
    public void stop() { player.stopTrack(); out.requestFlush(); }
    public void setVolume(int v) { out.setGain(Math.clamp(v, 0, 200) / 100f); }

    public void close() { running = false; }

    private void pumpLoop() {
        MutableAudioFrame frame = new MutableAudioFrame();
        byte[] buf = new byte[FORMAT.maximumChunkSize()];
        frame.setBuffer(ByteBuffer.wrap(buf));
        while (running) {
            boolean has = player.provide(frame);
            out.pump(buf, has ? frame.getDataLength() : 0, has);
            if (!has) sleep();
        }
        out.shutdown();
    }

    private static void sleep() { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
}
