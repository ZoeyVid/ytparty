package de.zoeyvid.ytparty.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SponsorBlock {
    public static final byte FLAG_ENABLED = 0x01;
    public static final byte FLAG_SPONSOR = 0x02;
    public static final byte FLAG_SELFPROMO = 0x04;
    public static final byte FLAG_MUSIC = 0x08;
    public static final byte FLAG_ALL = 0x0F;

    public record Segment(long startMs, long endMs, String category) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private SponsorBlock() {}

    public static boolean categoryEnabled(byte flags, String category) {
        return switch (category) {
            case "sponsor" -> (flags & FLAG_SPONSOR) != 0;
            case "selfpromo" -> (flags & FLAG_SELFPROMO) != 0;
            case "music_offtopic" -> (flags & FLAG_MUSIC) != 0;
            default -> false;
        };
    }

    public static String videoId(String uri) {
        try {
            URI u = URI.create(uri);
            String host = u.getHost();
            if (host == null) return null;
            if (host.contains("youtu.be")) { String p = u.getPath(); return p.length() > 1 ? p.substring(1) : null; }
            if (host.contains("youtube.com") && u.getQuery() != null)
                for (String kv : u.getQuery().split("&")) if (kv.startsWith("v=")) return kv.substring(2);
        } catch (RuntimeException ignored) {}
        return null;
    }

    public static void fetch(String videoId, Consumer<List<Segment>> onResult) {
        String prefix = hashPrefix(videoId);
        if (prefix == null) { onResult.accept(List.of()); return; }
        String url = "https://sponsor.ajay.app/api/skipSegments/" + prefix
            + "?categories=" + URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"music_offtopic\"]", StandardCharsets.UTF_8);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> onResult.accept(resp.statusCode() == 200 ? parse(resp.body(), videoId) : List.of()))
            .exceptionally(e -> { onResult.accept(List.of()); return null; });
    }

    private static List<Segment> parse(String body, String videoId) {
        List<Segment> out = new ArrayList<>();
        try {
            for (var entry : JsonParser.parseString(body).getAsJsonArray()) {
                JsonObject obj = entry.getAsJsonObject();
                if (!videoId.equals(obj.get("videoID").getAsString())) continue;
                for (var seg : obj.getAsJsonArray("segments")) {
                    JsonObject s = seg.getAsJsonObject();
                    JsonArray range = s.getAsJsonArray("segment");
                    out.add(new Segment((long) (range.get(0).getAsDouble() * 1000), (long) (range.get(1).getAsDouble() * 1000), s.get("category").getAsString()));
                }
            }
        } catch (RuntimeException ignored) {}
        return out;
    }

    private static String hashPrefix(String videoId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes(StandardCharsets.UTF_8));
            return "%02x%02x".formatted(h[0], h[1]);
        } catch (Exception e) { return null; }
    }
}
