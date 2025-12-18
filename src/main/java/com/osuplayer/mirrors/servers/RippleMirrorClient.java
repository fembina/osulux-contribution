package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osuplayer.osu.OsuApiClient;


public class RippleMirrorClient {

    private static final String SEARCH_ENDPOINT = "https://storage.ripple.moe/api/search";
    private static final String DOWNLOAD_ENDPOINT = "https://storage.ripple.moe/d/";
    private static final String USER_AGENT = "Osulux/1.0 (mirror)";

        private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public OsuApiClient.BeatmapSearchResult search(String query,
                                                   OsuApiClient.BeatmapMode mode,
                                                   OsuApiClient.BeatmapStatus status,
                                                   int offset,
                                                   int amount) throws IOException {
        if (amount <= 0) amount = 50;
        if (offset < 0) offset = 0;

        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT)
                .append("?amount=").append(amount)
                .append("&offset=").append(offset);
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(encode(query));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("El mirror Ripple devolvió status " + response.statusCode());
        }

        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            summaries.add(deserializeSet(element.getAsJsonObject()));
        }
        return new OsuApiClient.BeatmapSearchResult(summaries, null, offset + summaries.size());
    }

    public Path downloadBeatmapset(long beatmapsetId) throws IOException {
        Path tempFile = Files.createTempFile("osulux-ripple-" + beatmapsetId, ".osz");
        HttpRequest request = HttpRequest.newBuilder(URI.create(DOWNLOAD_ENDPOINT + beatmapsetId))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<Path> response = sendToFile(request, tempFile);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return tempFile;
        }
        Files.deleteIfExists(tempFile);
        throw new IOException("No se pudo descargar el beatmapset desde el mirror (status " + response.statusCode() + ")");
    }

    private OsuApiClient.BeatmapsetSummary deserializeSet(JsonObject obj) {
        long setId = obj.get("SetID").getAsLong();
        String title = getString(obj, "Title");
        String artist = getString(obj, "Artist");
        String creator = getString(obj, "Creator");
        int rankedStatus = obj.get("RankedStatus").getAsInt();
        String status = mapStatus(rankedStatus);
        boolean hasVideo = obj.has("HasVideo") && obj.get("HasVideo").getAsBoolean();
        int favourites = obj.has("Favourites") && obj.get("Favourites").isJsonPrimitive()
                ? obj.get("Favourites").getAsInt() : 0;
        double bpm = extractBpm(obj);
        int playCount = extractPlayCount(obj);
        Instant lastUpdated = parseInstant(getString(obj, "LastUpdate"));
        String coverUrl = "https://assets.ppy.sh/beatmaps/" + setId + "/covers/cover.jpg";

        return new OsuApiClient.BeatmapsetSummary(setId, title, artist, creator, status,
                bpm, hasVideo, favourites, playCount, lastUpdated, coverUrl);
    }

    private double extractBpm(JsonObject obj) {
        if (obj.has("ChildrenBeatmaps") && obj.get("ChildrenBeatmaps").isJsonArray()) {
            JsonArray diffs = obj.getAsJsonArray("ChildrenBeatmaps");
            for (JsonElement diffElement : diffs) {
                if (diffElement.isJsonObject() && diffElement.getAsJsonObject().has("BPM")) {
                    return diffElement.getAsJsonObject().get("BPM").getAsDouble();
                }
            }
        }
        return 0d;
    }

    private int extractPlayCount(JsonObject obj) {
        int total = 0;
        if (obj.has("ChildrenBeatmaps") && obj.get("ChildrenBeatmaps").isJsonArray()) {
            for (JsonElement diff : obj.getAsJsonArray("ChildrenBeatmaps")) {
                if (diff.isJsonObject() && diff.getAsJsonObject().has("Playcount")) {
                    total += diff.getAsJsonObject().get("Playcount").getAsInt();
                }
            }
        }
        return total;
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001")) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String mapStatus(int code) {
        return switch (code) {
            case -2 -> "graveyard";
            case -1 -> "wip";
            case 0 -> "pending";
            case 1 -> "ranked";
            case 2 -> "approved";
            case 3 -> "qualified";
            case 4 -> "loved";
            default -> "desconocido";
        };
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("La petición HTTP fue interrumpida", e);
        }
    }

    private HttpResponse<Path> sendToFile(HttpRequest request, Path target) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("La descarga desde el mirror fue interrumpida", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
