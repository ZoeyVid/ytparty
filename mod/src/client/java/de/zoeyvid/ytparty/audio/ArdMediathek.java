package de.zoeyvid.ytparty.audio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArdMediathek {
    private static final Pattern URL = Pattern.compile("https?://(?:(?:beta|www)\\.)?ardmediathek\\.de/(?:[^/]+/)?(?:player|video)/(?:[^?#]+/)?([a-zA-Z0-9]+)/?(?:[?#].*)?");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private ArdMediathek() {}

    static String id(String url) {
        Matcher m = URL.matcher(url);
        return m.matches() ? m.group(1) : null;
    }

    static MusicPlayer.Media resolve(String identifier, String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.ardmediathek.de/page-gateway/pages/ard/item/" + id + "?embedded=false&mcV6=true")).timeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(15, TimeUnit.SECONDS);
        if (response.statusCode() != 200) throw new IOException("ARD Mediathek API returned " + response.statusCode());
        for (JsonElement widget : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("widgets")) {
            JsonObject player = widget.getAsJsonObject();
            if (!"player_ondemand".equals(string(player, "type"))) continue;
            String title = Objects.requireNonNullElse(string(player, "title"), "ARD Mediathek");
            JsonObject show = object(player, "show");
            String series = show == null ? null : string(show, "title");
            if (series != null && !normalized(title).contains(normalized(series))) title = series.strip() + " – " + title;
            if (bool(player, "blockedByFsk")) throw new MusicPlayer.Unplayable("This ARD Mediathek video is age-restricted and only available late in the evening");
            if (bool(player, "blockedByLoginOnly")) throw new MusicPlayer.Unplayable("This ARD Mediathek video needs a login");
            NavigableMap<Integer, MusicPlayer.Video> videos = mp4s(player);
            if (videos.isEmpty()) throw new MusicPlayer.Unplayable("This ARD Mediathek video has no MP4 version");
            String url = videos.firstEntry().getValue().url();
            return new MusicPlayer.Media(identifier, title, url, videos, MusicPlayer.slimTrack(title, url, object(object(object(player, "mediaCollection"), "embedded"), "meta") instanceof JsonObject meta ? integer(meta, "durationSeconds", 0) * 1000L : 0));
        }
        throw new IOException("No ARD Mediathek video found");
    }

    static boolean geoBlocked(String url) {
        try {
            HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).HEAD().timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.discarding());
            return response.previousResponse().isPresent() || response.statusCode() / 100 == 3;
        } catch (Exception e) { return false; }
    }

    private static NavigableMap<Integer, MusicPlayer.Video> mp4s(JsonObject player) {
        NavigableMap<Integer, MusicPlayer.Video> german = new TreeMap<>(), other = new TreeMap<>();
        JsonObject collection = object(player, "mediaCollection");
        JsonObject embedded = collection == null ? null : object(collection, "embedded");
        if (embedded == null || !embedded.has("streams")) return other;
        for (JsonElement s : embedded.getAsJsonArray("streams")) {
            JsonObject stream = s.getAsJsonObject();
            if (!"main".equals(string(stream, "kind")) || !stream.has("media")) continue;
            for (JsonElement e : stream.getAsJsonArray("media")) {
                JsonObject media = e.getAsJsonObject();
                if (!"video/mp4".equals(string(media, "mimeType")) || string(media, "url") == null) continue;
                if (!media.has("audios") || media.getAsJsonArray("audios").isEmpty()) continue;
                JsonObject audio = media.getAsJsonArray("audios").get(0).getAsJsonObject();
                if (!"standard".equals(string(audio, "kind"))) continue;
                int height = integer(media, "maxVResolutionPx", 0);
                ("deu".equals(string(audio, "languageCode")) ? german : other).putIfAbsent(height > 0 ? height : Integer.MAX_VALUE, new MusicPlayer.Video(string(media, "url"), integer(media, "maxHResolutionPx", 0), height, "", "", false));
            }
        }
        return german.isEmpty() ? other : german;
    }

    private static String normalized(String s) { return s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-–—]+", " "); }

    static String string(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null; }
    static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null; }
    static int integer(JsonObject o, String key, int fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback; }
    static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean(); }
}
