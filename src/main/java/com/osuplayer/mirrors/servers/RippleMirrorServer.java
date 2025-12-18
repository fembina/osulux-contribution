package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorHttp;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;

public class RippleMirrorServer implements MirrorServer {

    private static final String SEARCH_ENDPOINT = "https://storage.ripple.moe/api/search";
    private static final String DOWNLOAD_ENDPOINT = "https://storage.ripple.moe/d/";

    @Override
    public String id() {
        return "ripple";
    }

    @Override
    public String displayName() {
        return "Mirror Ripple";
    }

    @Override
    public MirrorSearchResult search(String query,
                                     OsuApiClient.BeatmapMode mode,
                                     OsuApiClient.BeatmapStatus status,
                                     int pageIndex,
                                     int pageSize) throws IOException {
        int amount = pageSize <= 0 ? 50 : Math.min(pageSize, 100);
        int offset = Math.max(0, pageIndex) * amount;

        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT)
                .append("?amount=").append(amount)
                .append("&offset=").append(offset);
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(MirrorHttp.encode(query));
        }

        JsonArray array = MirrorHttp.getJsonArray(url.toString());
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            summaries.add(deserializeSet(element.getAsJsonObject()));
        }
        boolean hasMore = array.size() == amount;
        return new MirrorSearchResult(summaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        return MirrorHttp.downloadWithProgress(DOWNLOAD_ENDPOINT + beatmapsetId, "osulux-ripple-", false, null, beatmapsetId, listener);
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
}
