package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorHttp;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;

public class NerinyanMirrorServer implements MirrorServer {

    private static final String SEARCH_ENDPOINT = "https://api.nerinyan.moe/search";
    private static final String DOWNLOAD_ENDPOINT = "https://api.nerinyan.moe/d/%d";
    private static final int MAX_PAGE_SIZE = 60;

    @Override
    public String id() {
        return "nerinyan";
    }

    @Override
    public String displayName() {
        return "Nerinyan";
    }

    @Override
    public MirrorSearchResult search(String query,
                                     OsuApiClient.BeatmapMode mode,
                                     OsuApiClient.BeatmapStatus status,
                                     int pageIndex,
                                     int pageSize) throws IOException {
        int effectiveSize = pageSize <= 0 ? 40 : Math.min(pageSize, MAX_PAGE_SIZE);
        int page = Math.max(0, pageIndex);

        String payloadJson = buildPayload(query, mode, status, page);
        String encoded = Base64.getEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String url = SEARCH_ENDPOINT + "?ps=" + effectiveSize + "&b64=" + MirrorHttp.encode(encoded);

        JsonArray array = MirrorHttp.getJsonArray(url);
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            OsuApiClient.BeatmapsetSummary summary = deserializeSet(element.getAsJsonObject());
            if (summary != null) {
                summaries.add(summary);
            }
        }

        boolean hasMore = array.size() == effectiveSize;
        return new MirrorSearchResult(summaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_ENDPOINT, beatmapsetId);
        return MirrorHttp.downloadWithProgress(url, "osulux-nerinyan-", false, null, beatmapsetId, listener);
    }

    private String buildPayload(String query,
                                OsuApiClient.BeatmapMode mode,
                                OsuApiClient.BeatmapStatus status,
                                int page) {
        JsonObject root = new JsonObject();
        root.addProperty("extra", "");
        root.addProperty("ranked", mapStatus(status));
        root.addProperty("nsfw", true);
        root.addProperty("option", "");
        root.addProperty("m", mapMode(mode));
        root.add("totalLength", rangeObject());
        root.add("maxCombo", rangeObject());
        root.add("difficultyRating", rangeObject());
        root.add("accuracy", rangeObject());
        root.add("ar", rangeObject());
        root.add("cs", rangeObject());
        root.add("drain", rangeObject());
        root.add("bpm", rangeObject());
        root.addProperty("sort", "ranked_desc");
        root.addProperty("page", page);
        root.addProperty("query", query == null ? "" : query.trim());
        return root.toString();
    }

    private JsonObject rangeObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("min", 0);
        obj.addProperty("max", 0);
        return obj;
    }

    private String mapMode(OsuApiClient.BeatmapMode mode) {
        if (mode == null || mode == OsuApiClient.BeatmapMode.ANY) {
            return "";
        }
        return switch (mode) {
            case OSU -> "0";
            case TAIKO -> "1";
            case CATCH -> "2";
            case MANIA -> "3";
            default -> "";
        };
    }

    private String mapStatus(OsuApiClient.BeatmapStatus status) {
        if (status == null || status == OsuApiClient.BeatmapStatus.ANY) {
            return "all";
        }
        return switch (status) {
            case RANKED -> "ranked";
            case QUALIFIED -> "qualified";
            case LOVED -> "loved";
            case PENDING -> "pending,wip";
            case GRAVEYARD -> "graveyard";
            default -> "all";
        };
    }

    private OsuApiClient.BeatmapsetSummary deserializeSet(JsonObject obj) {
        long id = getLong(obj, "id", -1);
        if (id <= 0) {
            return null;
        }
        String title = getString(obj, "title");
        String artist = getString(obj, "artist");
        String creator = getString(obj, "creator");
        String status = getString(obj, "status");
        double bpm = obj.has("bpm") && obj.get("bpm").isJsonPrimitive()
                ? obj.get("bpm").getAsDouble()
                : 0d;
        boolean video = obj.has("video") && obj.get("video").getAsBoolean();
        int favourites = (int) getLong(obj, "favourite_count", 0);
        int playCount = (int) getLong(obj, "play_count", 0);
        Instant updated = parseInstant(getString(obj, "last_updated"));
        String coverUrl = String.format("https://assets.ppy.sh/beatmaps/%d/covers/list.jpg", id);

        return new OsuApiClient.BeatmapsetSummary(id,
                title,
                artist,
                creator,
                status,
                bpm,
                video,
                favourites,
                playCount,
                updated,
                coverUrl);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLong(JsonObject obj, String key, long fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsLong();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : "";
    }
}