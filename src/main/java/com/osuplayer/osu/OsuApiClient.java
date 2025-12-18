package com.osuplayer.osu;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osuplayer.config.ConfigManager;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.lang.I18n;

public class OsuApiClient {

    private static final String API_BASE = "https://osu.ppy.sh/api/v2";
    private static final String TOKEN_ENDPOINT = "https://osu.ppy.sh/oauth/token";

    private final ConfigManager configManager;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private String clientCredentialsToken;
    private Instant clientTokenExpiry = Instant.EPOCH;

    private String userAccessToken;
    private Instant userTokenExpiry = Instant.EPOCH;
    private String userRefreshToken;

    public OsuApiClient(ConfigManager configManager) {
        this.configManager = configManager;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        this.userAccessToken = emptyToNull(configManager.getOsuUserAccessToken());
        long storedExpiry = configManager.getOsuUserAccessTokenExpiry();
        if (userAccessToken != null && storedExpiry > 0) {
            this.userTokenExpiry = Instant.ofEpochSecond(storedExpiry);
        }
        this.userRefreshToken = emptyToNull(configManager.getOsuUserRefreshToken());
    }

    public synchronized void invalidateClientToken() {
        clientCredentialsToken = null;
        clientTokenExpiry = Instant.EPOCH;
    }

    public synchronized void clearUserSession() {
        userAccessToken = null;
        userRefreshToken = null;
        userTokenExpiry = Instant.EPOCH;
        configManager.clearOsuUserTokens();
    }

    public synchronized boolean hasUserSession() {
        return userAccessToken != null && !userAccessToken.isBlank();
    }

    public synchronized void applyUserToken(OAuthToken token) {
        if (token == null) return;
        applyUserTokenInternal(token.accessToken(), token.refreshToken(), token.expiresInSeconds());
    }

    public BeatmapSearchResult searchBeatmapSets(String query,
                                                 BeatmapMode mode,
                                                 BeatmapStatus status,
                                                 int offset,
                                                 String cursorString) throws IOException {
        String token = resolveTokenForSearch();

        StringBuilder url = new StringBuilder(API_BASE).append("/beatmapsets/search?");
        if (query != null && !query.isBlank()) {
            url.append("q=").append(encode(query)).append('&');
        }
        if (mode != null && mode != BeatmapMode.ANY) {
            url.append("m=").append(mode.apiValue).append('&');
        }
        if (status != null && status != BeatmapStatus.ANY) {
            url.append("s=").append(status.apiValue).append('&');
        }
        if (offset > 0) {
            url.append("offset=").append(Math.max(0, offset)).append('&');
        }
        if (cursorString != null && !cursorString.isBlank()) {
            url.append("cursor_string=").append(encode(cursorString)).append('&');
        }
        if (url.charAt(url.length() - 1) == '&' || url.charAt(url.length() - 1) == '?') {
            url.deleteCharAt(url.length() - 1);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
            .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("Error al buscar canciones en la API de osu!: status " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray setsArray = root.has("beatmapsets") && root.get("beatmapsets").isJsonArray()
                ? root.getAsJsonArray("beatmapsets")
                : new JsonArray();

        List<BeatmapsetSummary> summaries = new ArrayList<>();
        for (JsonElement element : setsArray) {
            if (!element.isJsonObject()) continue;
            BeatmapsetSummary summary = parseBeatmapsetSummary(element.getAsJsonObject());
            if (summary != null) {
                summaries.add(summary);
            }
        }

        String cursor = root.has("cursor_string") && !root.get("cursor_string").isJsonNull()
                ? root.get("cursor_string").getAsString()
                : null;
        int total = root.has("total") && root.get("total").isJsonPrimitive()
                ? root.get("total").getAsInt()
                : summaries.size();

        return new BeatmapSearchResult(summaries, cursor, total);
    }

    public BeatmapsetSummary fetchBeatmapset(long beatmapsetId) throws IOException {
        String token = resolveTokenForSearch();
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/beatmapsets/" + beatmapsetId))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("No se pudo obtener el beatmapset " + beatmapsetId + ": status " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        return parseBeatmapsetSummary(root);
    }

    public Path downloadBeatmapset(long beatmapsetId, boolean includeVideo) throws IOException {
        return downloadBeatmapset(beatmapsetId, includeVideo, null);
    }

    public Path downloadBeatmapset(long beatmapsetId,
                                   boolean includeVideo,
                                   DownloadProgressListener listener) throws IOException {
        String token = ensureUserToken();
        String url = API_BASE + "/beatmapsets/" + beatmapsetId + "/download" + (includeVideo ? "" : "?noVideo=1");
        Path tempFile = Files.createTempFile("osulux-beatmap-" + beatmapsetId, ".osz");

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();

        HttpResponse<InputStream> response = sendToStream(request);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(tempFile)) {
                copyWithProgress(input, output, beatmapsetId, total, listener);
                return tempFile;
            } catch (IOException ex) {
                Files.deleteIfExists(tempFile);
                throw ex;
            }
        }

        Files.deleteIfExists(tempFile);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("osu! rechazó la descarga (status " + response.statusCode() + "). Asegúrate de haber iniciado sesión con una cuenta válida y de que esta tenga acceso a las descargas.");
        }
        throw new IOException("No se pudo descargar el beatmapset " + beatmapsetId + ": status " + response.statusCode());
    }

    public OsuUser fetchCurrentUser() throws IOException {
        String token = ensureUserToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/me"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("No se pudo obtener la información del usuario: status " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        long id = getLong(root, "id", 0);
        String username = getString(root, "username");
        return new OsuUser(id, username);
    }
    private synchronized String resolveTokenForSearch() throws IOException {
        if (hasUserSession()) {
            return ensureUserToken();
        }
        return ensureClientToken();
    }

    private synchronized String ensureClientToken() throws IOException {
        boolean valid = clientCredentialsToken != null && Instant.now().isBefore(clientTokenExpiry.minusSeconds(60));
        if (valid) {
            return clientCredentialsToken;
        }

        String clientId = configManager.getOsuClientId();
        String clientSecret = configManager.getOsuClientSecret();
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IOException("Debes configurar client_id y client_secret de osu!");
        }

        String form = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=client_credentials"
                + "&scope=public";

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("No se pudo obtener el token OAuth de osu!: status " + response.statusCode());
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        clientCredentialsToken = root.get("access_token").getAsString();
        long expiresIn = root.get("expires_in").getAsLong();
        clientTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn));
        return clientCredentialsToken;
    }

    private synchronized String ensureUserToken() throws IOException {
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new IOException("Debes iniciar sesión con tu cuenta de osu! para descargar beatmaps.");
        }

        boolean valid = Instant.now().isBefore(userTokenExpiry.minusSeconds(60));
        if (valid) {
            return userAccessToken;
        }

        refreshUserToken();
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new IOException("No hay un token de usuario válido disponible. Inicia sesión nuevamente.");
        }
        return userAccessToken;
    }

    private synchronized void refreshUserToken() throws IOException {
        String refreshToken = userRefreshToken;
        if (refreshToken == null || refreshToken.isBlank()) {
            clearUserSession();
            throw new IOException("La sesión de osu! expiró. Inicia sesión nuevamente.");
        }

        String clientId = configManager.getOsuClientId();
        String clientSecret = configManager.getOsuClientSecret();
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IOException("Debes configurar client_id y client_secret de osu! para mantener la sesión.");
        }

        String form = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + encode(refreshToken);

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            clearUserSession();
            throw new IOException("No se pudo renovar el token de osu!: status " + response.statusCode());
        }

        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        String accessToken = root.get("access_token").getAsString();
        long expiresIn = root.get("expires_in").getAsLong();
        String newRefresh = root.has("refresh_token") && !root.get("refresh_token").isJsonNull()
                ? root.get("refresh_token").getAsString()
                : refreshToken;
        applyUserTokenInternal(accessToken, newRefresh, expiresIn);
    }

    private synchronized void applyUserTokenInternal(String accessToken, String refreshToken, long expiresInSeconds) {
        this.userAccessToken = accessToken;
        this.userTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresInSeconds));
        this.userRefreshToken = refreshToken;
        long epochSeconds = userTokenExpiry.getEpochSecond();
        configManager.setOsuUserTokens(accessToken, epochSeconds, refreshToken);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("La petición HTTP fue interrumpida", e);
        }
    }

    private HttpResponse<InputStream> sendToStream(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("La descarga fue interrumpida", e);
        }
    }

    private void copyWithProgress(InputStream input,
                                  OutputStream output,
                                  long beatmapsetId,
                                  long totalBytes,
                                  DownloadProgressListener listener) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long downloaded = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            downloaded += read;
            if (listener != null) {
                listener.onProgress(beatmapsetId, downloaded, totalBytes);
            }
        }
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private double getDouble(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsDouble() : 0d;
    }

    private long getLong(JsonObject obj, String key, long def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsLong() : def;
    }

    private String extractCoverUrl(JsonObject obj) {
        if (!obj.has("covers") || !obj.get("covers").isJsonObject()) return "";
        JsonObject covers = obj.getAsJsonObject("covers");
        if (covers.has("list")) return covers.get("list").getAsString();
        if (covers.has("cover")) return covers.get("cover").getAsString();
        return "";
    }

    private Instant parseTimestamp(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) return null;
        try {
            return Instant.parse(obj.get(field).getAsString());
        } catch (Exception ex) {
            return null;
        }
    }

    private BeatmapsetSummary parseBeatmapsetSummary(JsonObject obj) {
        if (obj == null) {
            return null;
        }
        long id = getLong(obj, "id", -1);
        if (id <= 0) {
            return null;
        }
        String title = getString(obj, "title");
        String artist = getString(obj, "artist");
        String creator = getString(obj, "creator");
        String statusValue = getString(obj, "status");
        double bpm = getDouble(obj, "bpm");
        boolean video = obj.has("video") && obj.get("video").getAsBoolean();
        int favourites = (int) getLong(obj, "favourite_count", 0);
        int playCount = (int) getLong(obj, "play_count", 0);
        Instant updatedAt = parseTimestamp(obj, "last_updated");
        String coverUrl = extractCoverUrl(obj);
        return new BeatmapsetSummary(id, title, artist, creator, statusValue,
                bpm, video, favourites, playCount, updatedAt, coverUrl);
    }

    public enum BeatmapMode {
        ANY(""), OSU("osu"), TAIKO("taiko"), CATCH("fruits"), MANIA("mania");

        private final String apiValue;
        BeatmapMode(String apiValue) { this.apiValue = apiValue; }

        @Override
        public String toString() {
            return switch (this) {
                case OSU -> "osu!standard";
                case TAIKO -> "osu!taiko";
                case CATCH -> "osu!catch";
                case MANIA -> "osu!mania";
                default -> I18n.tr("Cualquier modo");
            };
        }
    }

    public enum BeatmapStatus {
        ANY(""), RANKED("ranked"), LOVED("loved"), QUALIFIED("qualified"), PENDING("pending"), GRAVEYARD("graveyard");

        private final String apiValue;
        BeatmapStatus(String apiValue) { this.apiValue = apiValue; }

        @Override
        public String toString() {
            return switch (this) {
                case RANKED -> "Ranked";
                case LOVED -> "Loved";
                case QUALIFIED -> "Qualified";
                case PENDING -> "Pending";
                case GRAVEYARD -> "Graveyard";
                default -> I18n.tr("Cualquier estado");
            };
        }
    }

    public record BeatmapsetSummary(long id,
                                    String title,
                                    String artist,
                                    String creator,
                                    String status,
                                    double bpm,
                                    boolean video,
                                    int favouriteCount,
                                    int playCount,
                                    Instant lastUpdated,
                                    String coverUrl) {
        public String displayName() {
            return artist + " - " + title + " (" + creator + ")";
        }
    }

    public record BeatmapSearchResult(List<BeatmapsetSummary> beatmapsets,
                                      String cursorString,
                                      int total) {}

    public record OAuthToken(String accessToken,
                              String refreshToken,
                              long expiresInSeconds) {}

    public record OsuUser(long id,
                          String username) {}
}
