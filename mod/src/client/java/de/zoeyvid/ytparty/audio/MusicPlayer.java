package de.zoeyvid.ytparty.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainer;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
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
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.Utils;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
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
    static final String BLOCKED = "This site isn't in your allowed sites (Settings)";
    static final boolean SLIM = MusicPlayer.class.getResource("slim") != null;
    static final String NEEDS_FFMPEG = "The slim jar needs ffmpeg installed";

    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player = manager.createPlayer();
    private final OpenAlOutput out = new OpenAlOutput(FORMAT.sampleRate, FORMAT.maximumChunkSize());
    private volatile boolean running = true;
    private Runnable onEnd = () -> {};
    private Consumer<String> onError = reason -> {};
    private AudioTrack lastTrack;
    private volatile boolean decodeFinished;
    private volatile long seekTarget = -1, pendingSeek = -1, seekedAt, timecode, playedUntil, skipUntil = -1;
    private final AtomicInteger seeks = new AtomicInteger();

    private static final AtomicReferenceFieldUpdater<MusicPlayer, String> PLAYING = AtomicReferenceFieldUpdater.newUpdater(MusicPlayer.class, String.class, "playing");
    private static final ExecutorService RESOLVER = executor("ytparty-resolve"), ADDER = executor("ytparty-add");
    private static volatile boolean newPipeReady;
    private static final Map<String, Media> RESOLVED = new ConcurrentHashMap<>();
    static final Set<String> FFMPEG = ConcurrentHashMap.newKeySet();
    private static volatile List<String> allowedSites = List.of();
    private volatile String playing;
    private volatile Future<?> loading;
    private volatile Thread loader;
    private boolean mayRetry;

    public MusicPlayer() {
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.setFrameBufferDuration(1000);
        manager.setPlayerCleanupThreshold(Long.MAX_VALUE);
        Configurator.setLevel(LocalAudioTrackExecutor.class, Level.OFF);
        Configurator.setLevel("org.apache.http.impl.execchain.RetryExec", Level.WARN);
        if (!SLIM) manager.registerSourceManager(new HttpAudioSourceManager(new MediaContainerRegistry(MediaContainer.asList().stream().filter(probe -> probe != MediaContainer.OGG.probe && probe != MediaContainer.FLAC.probe).toList())));
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                if (reason != AudioTrackEndReason.FINISHED && reason != AudioTrackEndReason.LOAD_FAILED) return;
                boolean played = ((InternalAudioTrack) t).getActiveExecutor().getAudioBuffer().hasReceivedFrames(), early = played && mayRetry && seekTarget < 0 && t.isSeekable() && t.getPosition() < t.getDuration() - 1000 && !(t instanceof FfmpegAudioTrack f && !f.hls && !f.ranges);
                if (!(t instanceof FfmpegAudioTrack) && t.getUserData() instanceof Media media && media.track() != null && (reason == AudioTrackEndReason.LOAD_FAILED || (seekTarget >= 0 ? seekTarget : t.getPosition()) < t.getDuration() - 1000) && !(played && t.getPosition() == seekTarget)) { if (seekTarget < 0) seekedAt = System.nanoTime(); if (played || seekTarget >= 0) pendingSeek = seekTarget >= 0 ? seekTarget : t.getPosition() - out.bufferedAhead(); start(ffmpeg(media), media, null); }
                else if (reason == AudioTrackEndReason.FINISHED && (played || seekTarget >= 0 && !(t instanceof FfmpegAudioTrack f && f.failed)) && !t.getInfo().isStream && !early) { decodeFinished = true; if (played && seekTarget >= 0 && t.getPosition() == seekTarget) setPosition(seekTarget); }
                else { if (played) mayRetry = true; if (early) { seekedAt = playedUntil; pendingSeek = t.getPosition(); } failed(playing, t instanceof FfmpegAudioTrack && !Tools.installed("ffmpeg") ? (SLIM ? NEEDS_FFMPEG : (t.getInfo().isStream ? "Livestreams" : "Other sites") + " need ffmpeg installed") : null); }
            }
        });
        Thread pump = new Thread(this::pumpLoop, "ytparty-audio");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY);
        pump.start();
    }

    record Media(String identifier, String title, String url, NavigableMap<Integer, Video> videos, AudioTrack track, String source) {
        Media(String identifier, String title, String url, NavigableMap<Integer, Video> videos, AudioTrack track) { this(identifier, title, url, videos, track, identifier); }
    }
    public record Video(String url, int width, int height, String headers, String cookies, boolean hls, Video fallback) {
        Video(String url, int width, int height, String headers, String cookies, boolean hls) { this(url, width, height, headers, cookies, hls, null); }
    }

    static final Comparator<Double> FPS = Comparator.comparingDouble((Double fps) -> fps > 0 ? Math.max(fps / 30, 30 / fps) : Double.MAX_VALUE).thenComparing(Comparator.reverseOrder());

    static final class Unplayable extends Exception {
        Unplayable(String reason) { super(reason); }
    }

    private static ExecutorService executor(String name) { return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; }); }

    static boolean http(String url) { return url != null && url.matches("(?i)https?://\\S+"); }

    private static Media media(String identifier) throws Exception {
        if (!http(identifier)) throw new Unplayable("Only http(s) URLs can be played");
        if (SLIM && !Tools.installed("ffmpeg")) throw new Unplayable(NEEDS_FFMPEG);
        Media cached = RESOLVED.get(identifier);
        if (cached != null && !blocked(identifier) && !blocked(cached.source())) return cached;
        String ard = ArdMediathek.id(identifier);
        Media media = ard != null ? ArdMediathek.resolve(identifier, ard) : isYoutube(identifier) ? youtube(identifier) : YtDlp.resolve(identifier);
        media = new Media(blocked(media.identifier()) ? identifier : media.identifier(), media.title(), media.url(), media.videos(), media.track(), identifier);
        RESOLVED.put(identifier, media);
        if (builtIn(identifier) || !builtIn(media.identifier()) && origin(identifier).equalsIgnoreCase(origin(media.identifier()))) RESOLVED.put(media.identifier(), media);
        return media;
    }

    private static boolean builtIn(String url) throws Exception { return ArdMediathek.id(url) != null || isYoutube(url); }

    private static boolean blocked(String url) throws Exception { return !allowed(url) && !builtIn(url); }

    public static List<String> allowedSites() { return allowedSites; }

    static boolean allowed(String url) {
        String u = lowerOrigin(url);
        return !url.matches("(?is)[^?#]*?(?:[/\\\\]|%2f|%5c)(?:\\.|%2e){1,2}(?:[/\\\\?#;]|%2f|%5c|$).*") && allowedSites.stream().map(MusicPlayer::lowerOrigin).anyMatch(site -> u.startsWith(site) && (site.matches(".*[/?#]") || u.substring(site.length()).matches("([/?#].*)?")));
    }

    private static String lowerOrigin(String url) { String origin = origin(url); return origin.toLowerCase(Locale.ROOT) + url.substring(origin.length()); }

    static String origin(String url) { return url.replaceFirst("(?s)([^/?#]*(//[^/?#]*)?).*", "$1"); }

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
        if (!live(info)) { AudioStream audio = bestAudio(info); return new Media(identifier, info.getName(), audio.getContent(), videoUrls(info), slimTrack(info.getName(), audio.getContent(), audio.getItagItem() instanceof ItagItem itag && itag.getApproxDurationMs() > 0 ? itag.getApproxDurationMs() : info.getDuration() * 1000)); }
        if (!Tools.installed("ffmpeg")) throw new Unplayable("Livestreams need ffmpeg installed");
        String url = Utils.isNullOrEmpty(info.getHlsUrl()) ? info.getDashMpdUrl() : info.getHlsUrl();
        if (Utils.isNullOrEmpty(url)) throw new Unplayable("This YouTube livestream has no playable stream");
        return new Media(identifier, info.getName(), url, YtDlp.hlsVideos(url), new FfmpegAudioTrack(new AudioTrackInfo(info.getName(), "", Units.DURATION_MS_UNKNOWN, url, true, url), "", "", !Utils.isNullOrEmpty(info.getHlsUrl()), true));
    }

    static AudioTrack slimTrack(String title, String url, long duration) throws Exception { return SLIM ? new FfmpegAudioTrack(new AudioTrackInfo(title, "", duration > 0 ? duration : YtDlp.duration(YtDlp.probe(url, "", "")), url, false, url), "", "", false, false) : null; }

    private static void forget(String identifier) { RESOLVED.remove(identifier); }

    private static AudioStream bestAudio(StreamInfo info) throws Unplayable {
        return info.getAudioStreams().stream().filter(stream -> stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && stream.getContent() != null && !stream.getContent().isBlank())
            .max(Comparator.comparing((AudioStream stream) -> stream.getAudioTrackType() == AudioTrackType.ORIGINAL).thenComparingInt(AudioStream::getAverageBitrate))
            .orElseThrow(() -> new Unplayable("This YouTube video has no playable audio stream"));
    }

    private static NavigableMap<Integer, Video> videoUrls(StreamInfo info) {
        NavigableMap<Integer, VideoStream> best = new TreeMap<>();
        for (VideoStream stream : info.getVideoOnlyStreams()) {
            if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || stream.getContent() == null || stream.getContent().isBlank()) continue;
            best.merge(stream.getHeight(), stream, (a, b) -> FPS.compare((double) b.getFps(), (double) a.getFps()) < 0 ? b : a);
        }
        NavigableMap<Integer, Video> videos = new TreeMap<>();
        best.forEach((height, stream) -> videos.put(height, new Video(stream.getContent(), stream.getWidth(), height, "", "", false)));
        return videos;
    }

    private static boolean live(StreamInfo info) {
        return info.getStreamType() == StreamType.LIVE_STREAM || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
    }

    public void setOnEnd(Runnable r) { onEnd = r != null ? r : () -> {}; }
    public void setOnError(Consumer<String> c) { onError = c != null ? c : reason -> {}; }

    public void setAllowedSites(String lines) {
        allowedSites = lines.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        RESOLVED.clear();
        String identifier = playing;
        AudioTrack last = lastTrack;
        try {
            if (identifier != null && blocked(identifier) || last != null && last.getUserData() instanceof Media media && (blocked(media.identifier()) || blocked(media.source()))) {
                lastTrack = null;
                if (identifier != null) { if (loading != null) loading.cancel(true); failed(identifier, BLOCKED); }
            }
        } catch (Exception ignored) {}
    }

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
        if (media.track() instanceof FfmpegAudioTrack track && (SLIM || !track.isSeekable() || track.hls || FFMPEG.contains(media.identifier()))) { start(track.makeClone(), media, onTitle); return; }
        loader = Thread.ofVirtual().start(() -> manager.loadItemSync(media.url(), new AudioLoadResultHandler() {
            public void trackLoaded(AudioTrack track) { if (identifier.equals(playing)) start(media.track() == null || Math.abs(track.getDuration() - media.track().getDuration()) < 5000 ? track : ffmpeg(media), media, onTitle); }
            public void playlistLoaded(AudioPlaylist list) { if (list.getTracks().isEmpty()) noMatches(); else trackLoaded(pick(list)); }
            public void noMatches() { if (identifier.equals(playing)) { if (media.track() != null) start(ffmpeg(media), media, onTitle); else failed(identifier, null); } }
            public void loadFailed(FriendlyException e) { noMatches(); }
        }));
    }

    private static AudioTrack ffmpeg(Media media) { FFMPEG.add(media.identifier()); return media.track().makeClone(); }

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
        skipUntil = -1;
        playedUntil = System.nanoTime();
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
        if (seek >= 0) setPosition(player.isPaused() ? seek : seek + (System.nanoTime() - seekedAt) / 1_000_000);
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
        seekedAt = System.nanoTime();
        if (t != null && t.isSeekable()) { long p = Math.max(0, Math.min(t.getDuration() - 1, ms)); t.setPosition(p); seekTarget = p; seeks.incrementAndGet(); out.requestFlush(); }
    }

    public long position() {
        long target = seekTarget;
        if (target >= 0) return target;
        AudioTrack t = player.getPlayingTrack();
        return Math.max(0, (t != null ? t.getPosition() : decodeFinished ? timecode : 0) - out.bufferedAhead());
    }
    public long elapsed() { return player.getPlayingTrack() instanceof FfmpegAudioTrack t ? (System.nanoTime() - t.began) / 1_000_000 : 0; }
    public long duration() { AudioTrack t = player.getPlayingTrack(); if (t == null && decodeFinished) t = lastTrack; return t != null ? t.getDuration() : 0; }

    public void setPaused(boolean paused) { if (!paused && player.isPaused()) seekedAt = System.nanoTime(); player.setPaused(paused); out.requestPause(paused); }
    public boolean isPaused() { return player.isPaused(); }
    public boolean seeking() { return seekTarget >= 0; }
    public boolean live() { AudioTrack t = player.getPlayingTrack(); if (t == null && decodeFinished) t = lastTrack; return t != null && !t.isSeekable(); }
    public int seeks() { return seeks.get(); }
    public Video video(int height) {
        AudioTrack t = player.getPlayingTrack();
        NavigableMap<Integer, Video> videos = t != null ? ((Media) t.getUserData()).videos() : Collections.emptyNavigableMap();
        return videos.isEmpty() ? null : Objects.requireNonNullElse(videos.ceilingEntry(height - height / 10), videos.lastEntry()).getValue();
    }
    public void stop() { pendingSeek = -1; halt(); playing = null; if (loader != null) loader.interrupt(); }
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
                if (has && seekTarget >= 0 && !stale) { seekTarget = -1; skipUntil = player.isPaused() ? -1 : tc + (System.nanoTime() - seekedAt) / 1_000_000; }
                if (has && !stale && tc >= skipUntil) { out.write(buf, frame.getDataLength()); if (out.bufferedAhead() > 200) playedUntil = System.nanoTime() + out.bufferedAhead() * 1_000_000L; }
                if (decodeFinished && out.bufferedAhead() == 0) { decodeFinished = false; onEnd.run(); }
                if (!has) sleep();
            } catch (Exception ignored) { sleep(); }
        }
        out.shutdown();
    }

    private static void sleep() { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
}
