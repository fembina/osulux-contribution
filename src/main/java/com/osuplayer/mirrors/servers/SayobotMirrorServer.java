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

public class SayobotMirrorServer implements MirrorServer {

    private static final String SEARCH_ENDPOINT = "https://api.sayobot.cn/beatmaplist";
    private static final String DOWNLOAD_ENDPOINT = "https://dl.sayobot.cn/beatmaps/download/osz/%d?server=auto";
    private static final int MAX_PAGE_SIZE = 50;

    @Override
    public String id() {
        return "sayobot";
    }

    @Override
    public String displayName() {
        return "Sayobot";
    }

    @Override
    public MirrorSearchResult search(String query,
                                     OsuApiClient.BeatmapMode mode,
                                     OsuApiClient.BeatmapStatus status,
                                     int pageIndex,
                                     int pageSize) throws IOException {
        int effectiveSize = pageSize <= 0 ? 40 : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = Math.max(0, pageIndex) * effectiveSize;

        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT)
                .append("?L=").append(effectiveSize)
                .append("&O=").append(offset)
                .append("&T=4")
                .append("&M=").append(mapMode(mode))
                .append("&C=").append(mapStatus(status));
        if (query != null && !query.isBlank()) {
            url.append("&K=").append(MirrorHttp.encode(query.trim()));
        }

        JsonObject root = MirrorHttp.getJsonObject(url.toString());
        int statusCode = root.has("status") && root.get("status").isJsonPrimitive()
            ? root.get("status").getAsInt()
            : 0;
        if (statusCode != 0) {
            return new MirrorSearchResult(List.of(), false);
        }
        JsonArray data = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data")
                : new JsonArray();

        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>(data.size());
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            OsuApiClient.BeatmapsetSummary summary = deserializeBeatmap(element.getAsJsonObject());
            if (summary != null) {
                summaries.add(summary);
            }
        }

        boolean hasMore = root.has("endid") && root.get("endid").isJsonPrimitive()
                && root.get("endid").getAsInt() > 0;
        return new MirrorSearchResult(summaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_ENDPOINT, beatmapsetId);
        return MirrorHttp.downloadWithProgress(url,
                "osulux-sayobot-",
                true,
                "https://osu.sayobot.cn/",
                beatmapsetId,
                listener);
    }

    private OsuApiClient.BeatmapsetSummary deserializeBeatmap(JsonObject obj) {
        long id = getLong(obj, "sid");
        if (id <= 0) {
            return null;
        }
        String title = preferUnicode(getString(obj, "titleU"), getString(obj, "title"));
        String artist = preferUnicode(getString(obj, "artistU"), getString(obj, "artist"));
        String creator = getString(obj, "creator");
        String status = mapApprovedStatus(obj);
        double bpm = obj.has("bpm") && obj.get("bpm").isJsonPrimitive() ? obj.get("bpm").getAsDouble() : 0d;
        boolean video = false;
        int favourites = (int) getLong(obj, "favourite_count");
        int playCount = (int) getLong(obj, "play_count");
        Instant updated = parseInstant(obj);
        String cover = String.format("https://assets.ppy.sh/beatmaps/%d/covers/list.jpg", id);

        return new OsuApiClient.BeatmapsetSummary(id,
                title,
                artist,
                creator,
                status,
                bpm,
                video,
                Math.max(favourites, 0),
                Math.max(playCount, 0),
                updated,
                cover);
    }

    private String preferUnicode(String unicode, String fallback) {
        return (unicode != null && !unicode.isBlank()) ? unicode : fallback;
    }

    private Instant parseInstant(JsonObject obj) {
        if (!obj.has("lastupdate") || !obj.get("lastupdate").isJsonPrimitive()) {
            return null;
        }
        try {
            long seconds = obj.get("lastupdate").getAsLong();
            if (seconds <= 0) {
                return null;
            }
            return Instant.ofEpochSecond(seconds);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLong(JsonObject obj, String member) {
        if (obj.has(member) && obj.get(member).isJsonPrimitive()) {
            try {
                return obj.get(member).getAsLong();
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String getString(JsonObject obj, String member) {
        if (obj.has(member) && obj.get(member).isJsonPrimitive()) {
            return obj.get(member).getAsString();
        }
        return "";
    }

    private String mapApprovedStatus(JsonObject obj) {
        int approved = obj.has("approved") && obj.get("approved").isJsonPrimitive()
                ? obj.get("approved").getAsInt()
                : 0;
        return switch (approved) {
            case 1, 2 -> "ranked";
            case 3 -> "qualified";
            case 4 -> "loved";
            case -2 -> "graveyard";
            case -1, 0 -> "pending";
            default -> "pending";
        };
    }

    private int mapMode(OsuApiClient.BeatmapMode mode) {
        if (mode == null || mode == OsuApiClient.BeatmapMode.ANY) {
            return 0xFFFFFFFF;
        }
        return switch (mode) {
            case OSU -> 1;
            case TAIKO -> 2;
            case CATCH -> 4;
            case MANIA -> 8;
            default -> 0xFFFFFFFF;
        };
    }

    private int mapStatus(OsuApiClient.BeatmapStatus status) {
        if (status == null || status == OsuApiClient.BeatmapStatus.ANY) {
            return 0xFFFFFFFF;
        }
        return switch (status) {
            case RANKED -> 1;
            case QUALIFIED -> 2;
            case LOVED -> 4;
            case PENDING -> 8;
            case GRAVEYARD -> 16;
            default -> 0xFFFFFFFF;
        };
    }
}
