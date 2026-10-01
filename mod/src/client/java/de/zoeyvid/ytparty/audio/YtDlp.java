package de.zoeyvid.ytparty.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.zoeyvid.ytparty.audio.ArdMediathek.bool;
import static de.zoeyvid.ytparty.audio.ArdMediathek.integer;
import static de.zoeyvid.ytparty.audio.ArdMediathek.object;
import static de.zoeyvid.ytparty.audio.ArdMediathek.string;

final class YtDlp {
    private static final String HTTP = "[protocol~='^https?$']", HLS = "[protocol^=m3u8]";
    private static final Pattern DURATION = Pattern.compile("(?ims)(?<radio>^\\s+icy-.*?)?Duration: (?:N/A|(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}))(?<surround>.*?Audio: [^\\n]*? Hz, (?!mono|stereo|downmix|[12] channels))?");

    private YtDlp() {}

    static MusicPlayer.Media resolve(String url) throws Exception {
        if (!MusicPlayer.allowed(url)) throw new MusicPlayer.Unplayable(MusicPlayer.BLOCKED);
        String missing = Stream.of("yt-dlp", "ffmpeg").filter(tool -> !Tools.installed(tool)).collect(Collectors.joining(" and "));
        if (!missing.isEmpty()) throw new MusicPlayer.Unplayable("Other sites need " + missing + " installed");
        JsonElement info = JsonParser.parseString(output(new ProcessBuilder("yt-dlp", "--ignore-config", "--no-warnings", "--no-playlist", "-I", "1", "-J", "-f", "ba" + HTTP + "/ba" + HLS + "/b" + HTTP + "[height<=?480]/b" + HLS + "[height<=?480]/w" + HTTP + "/w" + HLS, "--", url)
            .redirectError(ProcessBuilder.Redirect.DISCARD), 60));
        while (info instanceof JsonObject playlist && playlist.get("entries") instanceof JsonArray entries) info = entries.isEmpty() ? JsonNull.INSTANCE : entries.get(0);
        if (!(info instanceof JsonObject media) || !MusicPlayer.http(string(media, "url"))) throw new MusicPlayer.Unplayable("yt-dlp couldn't read this URL");
        String audio = string(media, "url"), headers = headers(media), cookies = cookies(media), title = Objects.requireNonNullElse(string(media, "title"), url), page = string(media, "webpage_url");
        boolean live = bool(media, "is_live"), surround = integer(media, "audio_channels", 0) > 2;
        long duration = live ? Units.DURATION_MS_UNKNOWN : media.get("duration") instanceof JsonPrimitive seconds ? Math.round(seconds.getAsDouble() * 1000) : 0;
        if (duration == 0) {
            Matcher probe = probe(audio, headers, cookies);
            duration = duration(probe);
            live = duration == Units.DURATION_MS_UNKNOWN && probe.group("radio") != null;
            surround |= probe.group("surround") != null;
        }
        NavigableMap<Integer, JsonObject> best = new TreeMap<>();
        if ((live || duration != Units.DURATION_MS_UNKNOWN) && media.get("formats") instanceof JsonArray formats) for (JsonElement e : formats) if (e instanceof JsonObject format) {
            int height = integer(format, "height", 0);
            if (height > 0 && !"none".equals(string(format, "vcodec")) && MusicPlayer.http(string(format, "url")) && Objects.requireNonNullElse(string(format, "protocol"), "").matches("https?|m3u8(_native)?"))
                best.merge(height, format, (a, b) -> Comparator.comparingInt((JsonObject f) -> string(f, "protocol").startsWith("m3u8") ? 1 : string(f, "vcodec") == null ? 2 : 0).thenComparing(f -> f.get("fps") instanceof JsonPrimitive fps ? fps.getAsDouble() : 0d, MusicPlayer.FPS).compare(b, a) <= 0 ? b : a);
        }
        NavigableMap<Integer, MusicPlayer.Video> videos = new TreeMap<>();
        best.forEach((height, format) -> videos.put(height, new MusicPlayer.Video(string(format, "url"), integer(format, "width", 0), height, headers(format), cookies(format), string(format, "protocol").startsWith("m3u8"))));
        String identifier = MusicPlayer.http(page) ? page : url;
        FfmpegAudioTrack track = new FfmpegAudioTrack(new AudioTrackInfo(title, "", duration, audio, live, audio), headers, cookies, Objects.requireNonNullElse(string(media, "protocol"), "").startsWith("m3u8"));
        if (!MusicPlayer.SLIM && track.isSeekable() && !track.hls && (surround || !ranges(audio))) MusicPlayer.FFMPEG.addAll(List.of(url, identifier));
        return new MusicPlayer.Media(identifier, title, audio, videos, track);
    }

    private static boolean ranges(String url) throws InterruptedException {
        try {
            HttpResponse<InputStream> response = ArdMediathek.HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=1-1").timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            return response.statusCode() == 206;
        } catch (IOException | IllegalArgumentException e) { return false; }
    }

    static NavigableMap<Integer, MusicPlayer.Video> hlsVideos(String url) throws Exception {
        NavigableMap<Integer, MatchResult> best = new TreeMap<>();
        Pattern.compile("#EXT-X-STREAM-INF:(?=[^\\n]*RESOLUTION=(\\d+)x(\\d+))(?=(?:[^\\n]*FRAME-RATE=([\\d.]+))?)[^\\n]*\\n(?:#[^\\n]*\\n)*([^#\\s]\\S*)").matcher(ArdMediathek.HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString()).body())
            .results().forEach(variant -> best.merge(Integer.parseInt(variant.group(2)), variant, (a, b) -> Comparator.comparing((MatchResult v) -> v.group(3) == null ? 0d : Double.parseDouble(v.group(3)), MusicPlayer.FPS).compare(b, a) < 0 ? b : a));
        NavigableMap<Integer, MusicPlayer.Video> videos = new TreeMap<>();
        best.forEach((height, variant) -> videos.put(height, new MusicPlayer.Video(URI.create(url).resolve(variant.group(4)).toString(), Integer.parseInt(variant.group(1)), height, "", "", true)));
        return videos;
    }

    private static String headers(JsonObject format) {
        StringBuilder headers = new StringBuilder();
        JsonObject http = object(format, "http_headers");
        if (http != null) for (Map.Entry<String, JsonElement> header : http.entrySet()) if (header.getValue().isJsonPrimitive() && (header.getKey() + header.getValue().getAsString()).matches("[^\"\r\n]*")) headers.append(header.getKey()).append(": ").append(header.getValue().getAsString()).append("\r\n");
        return headers.toString();
    }

    private static String cookies(JsonObject format) {
        return Stream.ofNullable(string(format, "cookies")).flatMap(cookies -> Stream.of(cookies.split("; (?!(?:Domain|Path|Expires|Version)=|Secure(?:;|$))"))).filter(cookie -> cookie.matches("[^\"\r\n]*")).collect(Collectors.joining("\n"));
    }

    static Matcher probe(String url, String headers, String cookies) throws Exception {
        Matcher m = DURATION.matcher(output(new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-protocol_whitelist", FfmpegAudioTrack.PROTOCOLS, "-rw_timeout", "15000000", "-headers", headers, "-cookies", cookies, "-i", url).redirectErrorStream(true), 20));
        if (!m.find()) throw new IOException("ffmpeg couldn't read " + url);
        return m;
    }

    static long duration(Matcher probe) { return probe.group(2) == null ? Units.DURATION_MS_UNKNOWN : ((Long.parseLong(probe.group(2)) * 60 + Long.parseLong(probe.group(3))) * 60 + Long.parseLong(probe.group(4))) * 1000 + Long.parseLong(probe.group(5)) * 10; }

    private static String output(ProcessBuilder builder, int seconds) throws Exception {
        String name = builder.command().getFirst();
        Path file = Files.createTempFile("ytparty", ".out");
        try {
            Process process = Tools.start(builder.redirectOutput(file.toFile()));
            try { if (process.waitFor(seconds, TimeUnit.SECONDS)) return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
            finally { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            throw new MusicPlayer.Unplayable(name + " timed out");
        } catch (IOException e) {
            if (Tools.installed(name)) throw e;
            throw new MusicPlayer.Unplayable(MusicPlayer.SLIM && name.equals("ffmpeg") ? MusicPlayer.NEEDS_FFMPEG : "Other sites need " + name + " installed");
        } finally { if (!file.toFile().delete()) file.toFile().deleteOnExit(); }
    }
}
