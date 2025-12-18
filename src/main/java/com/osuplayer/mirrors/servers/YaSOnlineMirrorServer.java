package com.osuplayer.mirrors.servers;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorHttp;
import com.osuplayer.mirrors.MirrorSearchResult;
import com.osuplayer.mirrors.MirrorServer;

public class YaSOnlineMirrorServer implements MirrorServer {

    private static final String DOWNLOAD_URL = "https://osu.yas-online.net/json.mapdata.php?mapId=%d";
    private static final String DOWNLOAD_FETCH_URL = "https://osu.yas-online.net%s";
    private static final String HOME_URL = "https://osu.yas-online.net/json.maplist.php?o=%d";
    private static final String SEARCH_URL = "https://osu.yas-online.net/json.search.php?searchQuery=%s";
    private static final String PACK_DATA_URL = "https://osu.yas-online.net/json.packdata.php?themeId=%d&packNum=%d";
    private static final int PAGE_LIMIT = 25;

    @Override
    public String id() {
        return "yas";
    }

    @Override
    public String displayName() {
        return "YaS Online";
    }

    @Override
    public MirrorSearchResult search(String query, OsuApiClient.BeatmapMode mode, OsuApiClient.BeatmapStatus status, int pageIndex, int pageSize) throws IOException {
        boolean isSearch = query != null && !query.isBlank();
        String url = isSearch ? String.format(SEARCH_URL, encodeQuery(query))
                : String.format(HOME_URL, pageIndex * PAGE_LIMIT);
        JsonObject root = MirrorHttp.getJsonObject(url, true);
        if (!root.has("result") || !"success".equals(root.get("result").getAsString())) {
            return new MirrorSearchResult(List.of(), false);
        }
        JsonObject success = root.getAsJsonObject("success");
        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>();
        for (String key : success.keySet()) {
            JsonObject item = success.getAsJsonObject(key);
            long mapId = item.get("mapid").getAsLong();
            ArtistTitle artistTitle = splitArtistAndTitle(item.get("map").getAsString());
            int added = item.get("added").getAsInt();
            Instant date = added == 0 ? null : Instant.ofEpochSecond(added);
            int downloads = readInt(item, "downloads");
            summaries.add(new OsuApiClient.BeatmapsetSummary(mapId, artistTitle.title(), artistTitle.artist(), "",
                    "desconocido",
                    0, false, downloads, downloads, date, ""));
        }
        boolean hasMore = !isSearch && success.size() == PAGE_LIMIT;
        return new MirrorSearchResult(summaries, hasMore);
    }

    @Override
    public Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_URL, beatmapsetId);
        JsonObject root = MirrorHttp.getJsonObject(url, true);
        if (!root.has("result") || !"success".equals(root.get("result").getAsString())) {
            throw new IOException("El mirror YaS no devolvió un enlace de descarga.");
        }
        JsonObject success = root.getAsJsonObject("success");
        if (success.size() == 0) {
            throw new IOException("YaS Online no encontró el beatmap solicitado.");
        }
        JsonObject first = success.entrySet().iterator().next().getValue().getAsJsonObject();
        String downloadLink = first.get("downloadLink").getAsString();
        return MirrorHttp.downloadWithProgress(String.format(DOWNLOAD_FETCH_URL, downloadLink),
            "osulux-yas-",
            true,
            null,
            beatmapsetId,
            listener);
    }

    public PackInfo fetchPack(int themeId, int packNumber) throws IOException {
        if (packNumber <= 0) {
            throw new IOException("Ingresa un número de pack válido.");
        }
        int finalThemeId = themeId <= 0 ? 1 : themeId;
        String url = String.format(PACK_DATA_URL, finalThemeId, packNumber);
        JsonObject root = MirrorHttp.getJsonObject(url, true);
        if (!root.has("result") || !"success".equals(root.get("result").getAsString())) {
            String error = root.has("error") ? root.get("error").getAsString() : "Respuesta inesperada de YaS.";
            throw new IOException(error);
        }

        JsonObject data = root.getAsJsonObject("success");
        JsonObject mapsObject = data.has("maps") && data.get("maps").isJsonObject()
                ? data.getAsJsonObject("maps")
                : new JsonObject();

        List<OsuApiClient.BeatmapsetSummary> summaries = new ArrayList<>(mapsObject.size());
        for (Map.Entry<String, JsonElement> entry : mapsObject.entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            long mapId = item.get("mapid").getAsLong();
            ArtistTitle artistTitle = splitArtistAndTitle(getString(item, "map"));
            Instant added = parseEpochSeconds(item);
            int downloads = readInt(item, "downloads");
            String uploader = getString(data, "uploader");
            summaries.add(new OsuApiClient.BeatmapsetSummary(
                    mapId,
                    artistTitle.title(),
                    artistTitle.artist(),
                    uploader,
                    "Pack #" + packNumber,
                    0,
                    false,
                    downloads,
                    downloads,
                    added,
                    ""));
        }
        summaries.sort(Comparator.comparingLong(OsuApiClient.BeatmapsetSummary::id));

        String tag = getString(data, "osutag");
        String themeName = getString(data, "theme");
        String uploader = getString(data, "uploader");
        int beatmapCount = readInt(data, "beatmaps");
        long sizeBytes = readLong(data, "size");
        long downloadCount = readLong(data, "downloads");
        String downloadLink = getString(data, "downloadLink");

        return new PackInfo(finalThemeId,
                packNumber,
                tag,
                themeName,
                uploader,
                beatmapCount,
                sizeBytes,
                downloadCount,
                downloadLink,
                summaries);
    }

    private String encodeQuery(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private int readInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null || key.isBlank() || !obj.has(key)) {
            return "";
        }
        if (obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (ClassCastException ignored) {
            return obj.get(key).toString();
        }
    }

    private long readLong(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsLong();
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private Instant parseEpochSeconds(JsonObject obj) {
        if (!obj.has("added")) {
            return null;
        }
        try {
            long epoch = obj.get("added").getAsLong();
            return epoch <= 0 ? null : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ArtistTitle splitArtistAndTitle(String value) {
        if (value == null || value.isBlank()) {
            return new ArtistTitle("?", "?");
        }
        int idx = value.indexOf(" - ");
        if (idx > -1) {
            String artist = value.substring(0, idx).trim();
            String title = value.substring(idx + 3).trim();
            if (artist.isEmpty()) artist = "?";
            if (title.isEmpty()) title = value.trim();
            return new ArtistTitle(artist, title);
        }
        return new ArtistTitle("?", value.trim());
    }

    private record ArtistTitle(String artist, String title) { }

    public record PackInfo(int themeId,
                           int packNumber,
                           String packTag,
                           String themeName,
                           String uploader,
                           int beatmapCount,
                           long sizeBytes,
                           long downloadCount,
                           String downloadLink,
                           List<OsuApiClient.BeatmapsetSummary> maps) { }
}
