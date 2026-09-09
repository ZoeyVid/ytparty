package de.zoeyvid.ytparty.audio;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NewPipeDownloader extends Downloader {
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Override
    public Response execute(Request request) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofSeconds(30));
        for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            if (RESTRICTED.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
            for (String value : header.getValue()) builder.header(header.getKey(), value);
        }
        byte[] body = request.dataToSend();
        builder.method(request.httpMethod(), body != null ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), "", response.headers().map(), response.body(), response.uri().toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
