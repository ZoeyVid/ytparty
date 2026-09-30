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
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.Utils;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class MusicPlayer {
    private static final AudioDataFormat FORMAT = StandardAudioDataFormats.COMMON_PCM_S16_LE;

    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player = manager.createPlayer();
    private final OpenAlOutput out = new OpenAlOutput(FORMAT.sampleRate, FORMAT.maximumChunkSize());
    private volatile boolean running = true;
    private Runnable onEnd = () -> {};
    private Consumer<String> onError = reason -> {};
    private AudioTrack lastTrack;
    private volatile boolean decodeFinished;
    private volatile long seekTarget = -1, pendingSeek = -1, timecode;
    private final AtomicInteger seeks = new AtomicInteger();

    private static final AtomicReferenceFieldUpdater<MusicPlayer, String> PLAYING = AtomicReferenceFieldUpdater.newUpdater(MusicPlayer.class, String.class, "playing");
    private static final ExecutorService RESOLVER = executor("ytparty-resolve"), ADDER = executor("ytparty-add");
    private static volatile boolean newPipeReady;
    private static final Map<String, Media> RESOLVED = new ConcurrentHashMap<>();
    public static volatile boolean otherSites;
    private volatile String playing;
    private volatile Future<?> loading;
    private boolean mayRetry;

    public MusicPlayer() {
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.setFrameBufferDuration(1000);
        manager.setPlayerCleanupThreshold(Long.MAX_VALUE);
        manager.registerSourceManager(new HttpAudioSourceManager());
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                if (reason != AudioTrackEndReason.FINISHED && reason != AudioTrackEndReason.LOAD_FAILED) return;
                boolean played = ((InternalAudioTrack) t).getActiveExecutor().getAudioBuffer().hasReceivedFrames();
                if (reason == AudioTrackEndReason.FINISHED && (played || seekTarget >= 0 && !(t instanceof FfmpegAudioTrack f && f.failed)) && !t.getInfo().isStream) { decodeFinished = true; if (played && seekTarget >= 0 && t.getPosition() == seekTarget) setPosition(seekTarget); }
                else { if (played) mayRetry = true; failed(playing, t instanceof FfmpegAudioTrack && !Tools.installed("ffmpeg") ? (t.getInfo().isStream ? "Livestreams" : "Other sites") + " need ffmpeg installed" : null); }
            }
        });
        Thread pump = new Thread(this::pumpLoop, "ytparty-audio");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY);
        pump.start();
    }

    record Media(String identifier, String title, String url, NavigableMap<Integer, Video> videos, AudioTrack track) {}
    public record Video(String url, int width, int height, String headers) {}

    static final class Unplayable extends Exception {
        Unplayable(String reason) { super(reason); }
    }

    private static ExecutorService executor(String name) { return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; }); }

    static boolean http(String url) { return url != null && url.matches("(?i)https?://\\S+"); }

    private static Media media(String identifier) throws Exception {
        if (!http(identifier)) throw new Unplayable("Only http(s) URLs can be played");
        Media cached = RESOLVED.get(identifier);
        if (cached != null && (otherSites || builtIn(identifier))) return cached;
        String ard = ArdMediathek.id(identifier);
        Media media = ard != null ? ArdMediathek.resolve(identifier, ard) : isYoutube(identifier) ? youtube(identifier) : YtDlp.resolve(identifier);
        RESOLVED.put(identifier, media);
        if (builtIn(identifier) || !builtIn(media.identifier())) RESOLVED.put(media.identifier(), media);
        return media;
    }

    private static boolean builtIn(String url) throws Exception { return ArdMediathek.id(url) != null || isYoutube(url); }

    private static boolean isYoutube(String url) throws Exception {
        return ServiceList.YouTube.getStreamLHFactory().acceptUrl(withoutList(url)) || ServiceList.YouTube.getPlaylistLHFactory().acceptUrl(url);
    }

    private static String withoutList(String url) { return url.replaceAll("(?<=[?&])list=[^&#]*&?", ""); }

    private static Media youtube(String identifier) throws Exception {
        if (!newPipeReady) { NewPipe.init(new NewPipeDownloader()); newPipeReady = true; }
        String video = withoutList(identifier);
        if (!ServiceList.YouTube.getStreamLHFactory().acceptUrl(video)) {
            List<StreamInfoItem> items = PlaylistInfo.getInfo(ServiceList.YouTube, identifier).getRelatedItems();
            if (items.isEmpty()) throw new Unplayable("This YouTube playlist is empty");
            identifier = video = items.getFirst().getUrl();
        }
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, video);
        if (!live(info)) return new Media(identifier, info.getName(), bestAudioUrl(info), videoUrls(info), null);
        if (!Tools.installed("ffmpeg")) throw new Unplayable("Livestreams need ffmpeg installed");
        String url = Utils.isNullOrEmpty(info.getHlsUrl()) ? info.getDashMpdUrl() : info.getHlsUrl();
        if (Utils.isNullOrEmpty(url)) throw new Unplayable("This YouTube livestream has no playable stream");
        return new Media(identifier, info.getName(), url, Collections.emptyNavigableMap(), new FfmpegAudioTrack(new AudioTrackInfo(info.getName(), "", Units.DURATION_MS_UNKNOWN, url, true, url), "", true));
    }

    private static void forget(String identifier) { RESOLVED.remove(identifier); }

    private static String bestAudioUrl(StreamInfo info) throws Unplayable {
        AudioStream best = null;
        for (AudioStream stream : info.getAudioStreams()) {
            if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || stream.getContent() == null || stream.getContent().isBlank()) continue;
            if (best == null || stream.getAverageBitrate() > best.getAverageBitrate()) best = stream;
        }
        if (best == null) throw new Unplayable("This YouTube video has no playable audio stream");
        return best.getContent();
    }

    private static NavigableMap<Integer, Video> videoUrls(StreamInfo info) {
        NavigableMap<Integer, Video> videos = new TreeMap<>();
        for (VideoStream stream : info.getVideoOnlyStreams()) {
            if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || stream.getContent() == null || stream.getContent().isBlank()) continue;
            videos.putIfAbsent(stream.getHeight(), new Video(stream.getContent(), stream.getWidth(), stream.getHeight(), ""));
        }
        return videos;
    }

    private static boolean live(StreamInfo info) {
        return info.getStreamType() == StreamType.LIVE_STREAM || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
    }

    public void setOnEnd(Runnable r) { onEnd = r != null ? r : () -> {}; }
    public void setOnError(Consumer<String> c) { onError = c != null ? c : reason -> {}; }

    public void resolveAll(String identifier, Consumer<List<String[]>> onDone) {
        ADDER.execute(() -> {
            try { Media media = media(identifier); onDone.accept(List.<String[]>of(new String[]{media.identifier(), media.title()})); }
            catch (Exception e) { onDone.accept(List.of()); }
        });
    }

    public void resolve(String identifier, BiConsumer<String, String> onResolved, Consumer<String> onFail) {
        ADDER.execute(() -> {
            try { Media media = media(identifier); onResolved.accept(media.identifier(), media.title()); }
            catch (Exception e) { onFail.accept(e instanceof Unplayable ? e.getMessage() : null); }
        });
    }

    public void playIdentifier(String identifier, Consumer<String> onTitle) {
        stop();
        playing = identifier;
        lastTrack = null;
        mayRetry = true;
        if (loading != null) loading.cancel(true);
        loading = RESOLVER.submit(() -> load(identifier, onTitle));
    }

    private void load(String identifier, Consumer<String> onTitle) {
        if (!identifier.equals(playing)) return;
        Media media;
        try { media = media(identifier); } catch (Exception e) { failed(identifier, e instanceof Unplayable ? e.getMessage() : null); return; }
        if (ArdMediathek.id(identifier) != null && ArdMediathek.geoBlocked(media.url())) { failed(identifier, null); return; }
        if (!identifier.equals(playing)) return;
        if (media.track() != null) { start(media.track().makeClone(), media, onTitle); return; }
        manager.loadItem(media.url(), new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { if (identifier.equals(playing)) start(track, media, onTitle); }
            public void playlistLoaded(AudioPlaylist list) { if (list.getTracks().isEmpty()) noMatches(); else trackLoaded(pick(list)); }
            public void noMatches() { failed(identifier, null); }
            public void loadFailed(FriendlyException e) { failed(identifier, null); }
        });
    }

    private void failed(String identifier, String reason) {
        String current = playing;
        if (current == null || !current.equals(identifier)) return;
        if (!mayRetry || reason != null) { if (PLAYING.compareAndSet(this, current, null)) { halt(); onError.accept(reason); } return; }
        mayRetry = false;
        if (seekTarget >= 0) pendingSeek = seekTarget;
        forget(identifier);
        loading = RESOLVER.submit(() -> load(identifier, null));
    }

    private void begin(AudioTrack track) {
        decodeFinished = false;
        seekTarget = -1;
        out.requestFlush();
        player.playTrack(track);
        seeks.incrementAndGet();
    }

    private void start(AudioTrack track, Media media, Consumer<String> onTitle) {
        track.setUserData(media);
        lastTrack = track;
        begin(track);
        long seek = pendingSeek;
        if (seek >= 0) setPosition(seek);
        if (onTitle != null) onTitle.accept(media.title() != null ? media.title() : track.getInfo().title);
    }

    public void repeatCurrent() { mayRetry = true; pendingSeek = -1; if (lastTrack != null) { playing = ((Media) lastTrack.getUserData()).identifier(); begin(lastTrack.makeClone()); } }

    private static AudioTrack pick(AudioPlaylist list) {
        return list.getSelectedTrack() != null ? list.getSelectedTrack() : list.getTracks().getFirst();
    }

    public void seekBy(long deltaMs) {
        AudioTrack t = player.getPlayingTrack();
        if (t != null && t.isSeekable()) setPosition(position() + deltaMs);
    }

    public void setPosition(long ms) {
        AudioTrack t = player.getPlayingTrack();
        if (t == null && decodeFinished && lastTrack != null) begin(t = lastTrack.makeClone());
        pendingSeek = t == null ? ms : -1;
        if (t != null && t.isSeekable()) { long p = Math.max(0, Math.min(t.getDuration() - 1, ms)); t.setPosition(p); seekTarget = p; seeks.incrementAndGet(); out.requestFlush(); }
    }

    public long position() {
        long target = seekTarget;
        if (target >= 0) return target;
        AudioTrack t = player.getPlayingTrack();
        return Math.max(0, (t != null ? t.getPosition() : decodeFinished ? timecode : 0) - out.bufferedAhead());
    }
    public long duration() { AudioTrack t = player.getPlayingTrack(); if (t == null && decodeFinished) t = lastTrack; return t != null ? t.getDuration() : 0; }

    public void setPaused(boolean paused) { player.setPaused(paused); out.requestPause(paused); }
    public boolean isPaused() { return player.isPaused(); }
    public boolean seeking() { return seekTarget >= 0; }
    public boolean live() { AudioTrack t = player.getPlayingTrack(); if (t == null && decodeFinished) t = lastTrack; return t != null && !t.isSeekable(); }
    public int seeks() { return seeks.get(); }
    public Video video(int height) {
        AudioTrack t = player.getPlayingTrack();
        NavigableMap<Integer, Video> videos = t != null ? ((Media) t.getUserData()).videos() : Collections.emptyNavigableMap();
        return videos.isEmpty() ? null : Objects.requireNonNullElse(videos.ceilingEntry(height - height / 10), videos.lastEntry()).getValue();
    }
    public void stop() { pendingSeek = -1; halt(); playing = null; }
    private void halt() { decodeFinished = false; seekTarget = -1; player.stopTrack(); out.requestFlush(); }
    public void setVolume(int v) { out.setGain(Math.clamp(v, 0, 200) / 100f); }

    public void close() { running = false; }

    private void pumpLoop() {
        MutableAudioFrame frame = new MutableAudioFrame();
        byte[] buf = new byte[FORMAT.maximumChunkSize()];
        frame.setBuffer(ByteBuffer.wrap(buf));
        while (running) {
            try {
                boolean has = out.ready() && player.provide(frame);
                long target = seekTarget, tc = frame.getTimecode();
                boolean stale = has && target >= 0 && Math.abs(tc - target) > 60 && (tc < target || tc > target + 5000 || Math.abs(tc - timecode - FORMAT.frameDuration()) <= 1);
                if (has) timecode = tc;
                if (has && seekTarget >= 0 && !stale) seekTarget = -1;
                if (has && !stale) out.write(buf, frame.getDataLength());
                if (decodeFinished && out.bufferedAhead() == 0) { decodeFinished = false; onEnd.run(); }
                if (!has) sleep();
            } catch (Exception ignored) { sleep(); }
        }
        out.shutdown();
    }

    private static void sleep() { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
}
