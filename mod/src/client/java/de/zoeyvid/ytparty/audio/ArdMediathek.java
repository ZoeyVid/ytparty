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
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArdMediathek {
    private static final Pattern URL = Pattern.compile("https?://(?:(?:beta|www)\\.)?ardmediathek\\.de/(?:[^/]+/)?(?:player|video)/(?:[^?#]+/)?([a-zA-Z0-9]+)/?(?:[?#].*)?");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private ArdMediathek() {}

    static String id(String url) {
        Matcher m = URL.matcher(url);
        return m.matches() ? m.group(1) : null;
    }

    static MusicPlayer.Media resolve(String id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.ardmediathek.de/page-gateway/pages/ard/item/" + id + "?embedded=false&mcV6=true")).timeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("ARD Mediathek API returned " + response.statusCode());
        for (JsonElement widget : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("widgets")) {
            JsonObject player = widget.getAsJsonObject();
            if (!"player_ondemand".equals(string(player, "type"))) continue;
            String title = string(player, "title");
            JsonObject show = object(player, "show");
            String series = show == null ? null : string(show, "title");
            if (series != null && title != null && !title.contains(series)) title = series + " – " + title;
            if (bool(player, "blockedByFsk") || bool(player, "blockedByLoginOnly")) return new MusicPlayer.Media(title, null, Collections.emptyNavigableMap());
            NavigableMap<Integer, MusicPlayer.Video> videos = mp4s(player);
            return new MusicPlayer.Media(title, videos.isEmpty() ? null : videos.firstEntry().getValue().url(), videos);
        }
        return new MusicPlayer.Media(null, null, Collections.emptyNavigableMap());
    }

    static boolean geoBlocked(String url) {
        try {
            HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).HEAD().timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.discarding());
            return response.previousResponse().isPresent() || response.statusCode() / 100 == 3;
        } catch (Exception e) { return false; }
    }

    private static NavigableMap<Integer, MusicPlayer.Video> mp4s(JsonObject player) {
        NavigableMap<Integer, MusicPlayer.Video> videos = new TreeMap<>();
        JsonObject collection = object(player, "mediaCollection");
        JsonObject embedded = collection == null ? null : object(collection, "embedded");
        if (embedded == null || !embedded.has("streams")) return videos;
        for (JsonElement s : embedded.getAsJsonArray("streams")) {
            JsonObject stream = s.getAsJsonObject();
            if (!"main".equals(string(stream, "kind")) || !stream.has("media")) continue;
            for (JsonElement e : stream.getAsJsonArray("media")) {
                JsonObject media = e.getAsJsonObject();
                if (!"video/mp4".equals(string(media, "mimeType")) || string(media, "url") == null) continue;
                if (!media.has("audios") || media.getAsJsonArray("audios").isEmpty() || !"standard".equals(string(media.getAsJsonArray("audios").get(0).getAsJsonObject(), "kind"))) continue;
                int height = integer(media, "maxVResolutionPx", Integer.MAX_VALUE);
                videos.putIfAbsent(height, new MusicPlayer.Video(string(media, "url"), integer(media, "maxHResolutionPx", 0), height));
            }
        }
        return videos;
    }

    private static String string(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null; }
    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null; }
    private static int integer(JsonObject o, String key, int fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback; }
    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean(); }
}
