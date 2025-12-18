package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorHttp;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;

public class CatboyMirrorServer implements MirrorServer {

    private static final String SEARCH_URL = "https://catboy.best/api/search";
    private static final String DOWNLOAD_URL = "https://catboy.best/d/%d";
    private static final int MAX_PAGE_SIZE = 100;

    @Override
    public String id() {
        return "catboy";
    }

    @Override
    public String displayName() {
        return "Catboy";
    }

    @Override
    public MirrorSearchResult search(String query,
                                     OsuApiClient.BeatmapMode mode,
                                     OsuApiClient.BeatmapStatus status,
                                     int pageIndex,
                                     int pageSize) throws IOException {
        int effectiveSize = Math.max(1, Math.min(pageSize > 0 ? pageSize : 50, MAX_PAGE_SIZE));
        int requested = Math.min(MAX_PAGE_SIZE, effectiveSize);
        int offset = Math.max(0, pageIndex) * effectiveSize;

        StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?amount=").append(requested)
                .append("&offset=").append(offset);
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(MirrorHttp.encode(query.trim()));
        }
        Integer modeValue = mapMode(mode);
        if (modeValue != null) {
            url.append("&mode=").append(modeValue);
        }

        JsonElement root = JsonParser.parseString(MirrorHttp.getString(url.toString(), true));
        JsonArray dataset = extractDataArray(root);
        int rawCount = dataset.size();
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>(Math.min(rawCount, effectiveSize));
        int limit = Math.min(rawCount, effectiveSize);
        for (int i = 0; i < limit; i++) {
            JsonElement element = dataset.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            summaries.add(deserializeSet(element.getAsJsonObject()));
        }
        boolean hasMore = determineHasMore(root, rawCount, offset, effectiveSize, requested);
        return new MirrorSearchResult(summaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_URL, beatmapsetId);
        return MirrorHttp.downloadWithProgress(url, "osulux-catboy-", true, null, beatmapsetId, listener);
    }

    private OsuApiClient.BeatmapsetSummary deserializeSet(JsonObject obj) {
        long setId = getLong(obj, "SetID", -1);
        String title = getString(obj, "Title");
        String artist = getString(obj, "Artist");
        String creator = getString(obj, "Creator");
        int rankedStatus = obj.has("RankedStatus") && obj.get("RankedStatus").isJsonPrimitive()
                ? obj.get("RankedStatus").getAsInt()
                : 0;
        double bpm = extractBpm(obj.get("ChildrenBeatmaps"));
        int playCount = sumPlaycount(obj.get("ChildrenBeatmaps"));
        boolean hasVideo = obj.has("HasVideo") && obj.get("HasVideo").getAsBoolean();
        int favourites = obj.has("Favourites") && obj.get("Favourites").isJsonPrimitive()
                ? obj.get("Favourites").getAsInt()
                : 0;
        Instant lastUpdate = parseInstant(getString(obj, "LastUpdate"));
        String coverUrl = setId > 0
                ? String.format("https://assets.ppy.sh/beatmaps/%d/covers/list.jpg", setId)
                : "";

        return new OsuApiClient.BeatmapsetSummary(setId,
                title,
                artist,
                creator,
                statusLabel(rankedStatus),
                bpm,
                hasVideo,
                favourites,
                playCount,
                lastUpdate,
                coverUrl);
    }

    private double extractBpm(JsonElement childrenElement) {
        if (childrenElement == null || !childrenElement.isJsonArray()) {
            return 0d;
        }
        JsonArray array = childrenElement.getAsJsonArray();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("BPM") && obj.get("BPM").isJsonPrimitive()) {
                    return obj.get("BPM").getAsDouble();
                }
            }
        }
        return 0d;
    }

    private int sumPlaycount(JsonElement childrenElement) {
        if (childrenElement == null || !childrenElement.isJsonArray()) {
            return 0;
        }
        int total = 0;
        JsonArray array = childrenElement.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("Playcount") && obj.get("Playcount").isJsonPrimitive()) {
                total += obj.get("Playcount").getAsInt();
            }
        }
        return total;
    }

    private Integer mapMode(OsuApiClient.BeatmapMode mode) {
        if (mode == null || mode == OsuApiClient.BeatmapMode.ANY) {
            return null;
        }
        return switch (mode) {
            case OSU -> 0;
            case TAIKO -> 1;
            case CATCH -> 2;
            case MANIA -> 3;
            default -> null;
        };
    }
    private JsonArray extractDataArray(JsonElement root) {
        if (root == null) {
            return new JsonArray();
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                return obj.getAsJsonArray("data");
            }
        }
        return new JsonArray();
    }

    private boolean determineHasMore(JsonElement root,
                                     int rawCount,
                                     int offset,
                                     int effectiveSize,
                                     int requested) {
        Boolean metaHint = null;
        if (root != null && root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("more") && obj.get("more").isJsonPrimitive()) {
                metaHint = obj.get("more").getAsBoolean();
            } else if (obj.has("hasMore") && obj.get("hasMore").isJsonPrimitive()) {
                metaHint = obj.get("hasMore").getAsBoolean();
            } else if (obj.has("count") && obj.get("count").isJsonPrimitive()) {
                try {
                    int total = obj.get("count").getAsInt();
                    metaHint = offset + effectiveSize < total;
                } catch (NumberFormatException ignored) {
                    metaHint = null;
                }
            }
        }
        if (metaHint != null) {
            return metaHint;
        }
        return rawCount >= requested;
    }

    private String statusLabel(int rankedStatus) {
        return switch (rankedStatus) {
            case 4 -> "loved";
            case 3 -> "qualified";
            case 2, 1 -> "ranked";
            case 0, -1 -> "pending";
            case -2 -> "graveyard";
            default -> "pending";
        };
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
        return obj.has(key) && obj.get(key).isJsonPrimitive()
                ? obj.get(key).getAsString()
                : "";
    }

}
