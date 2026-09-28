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
            if (bool(player, "blockedByFsk") || bool(player, "blockedByLoginOnly")) return new MusicPlayer.Media(title, null);
            return new MusicPlayer.Media(title, smallestMp4(player));
        }
        return new MusicPlayer.Media(null, null);
    }

    private static String smallestMp4(JsonObject player) {
        JsonObject collection = object(player, "mediaCollection");
        JsonObject embedded = collection == null ? null : object(collection, "embedded");
        if (embedded == null || !embedded.has("streams")) return null;
        String best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (JsonElement s : embedded.getAsJsonArray("streams")) {
            JsonObject stream = s.getAsJsonObject();
            if (!"main".equals(string(stream, "kind")) || !stream.has("media")) continue;
            for (JsonElement e : stream.getAsJsonArray("media")) {
                JsonObject media = e.getAsJsonObject();
                if (!"video/mp4".equals(string(media, "mimeType")) || string(media, "url") == null) continue;
                if (!media.has("audios") || media.getAsJsonArray("audios").isEmpty() || !"standard".equals(string(media.getAsJsonArray("audios").get(0).getAsJsonObject(), "kind"))) continue;
                int height = media.has("maxHResolutionPx") && !media.get("maxHResolutionPx").isJsonNull() ? media.get("maxHResolutionPx").getAsInt() : Integer.MAX_VALUE - 1;
                if (height < bestHeight) { bestHeight = height; best = string(media, "url"); }
            }
        }
        return best;
    }

    private static String string(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null; }
    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null; }
    private static boolean bool(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean(); }
}
