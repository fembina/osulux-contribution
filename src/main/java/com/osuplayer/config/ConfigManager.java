package com.osuplayer.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ConfigManager {

    private static final String CONFIG_FILE = "config.properties";
    private static final Path CONFIG_DIR = Paths.get("config");
    private final Path configFilePath = CONFIG_DIR.resolve(CONFIG_FILE);
    private final Properties props = new Properties();
    private final SongDataStore songStore;

    public ConfigManager() {
        ensureConfigDirectory();
        migrateLegacyFile(Paths.get(CONFIG_FILE), configFilePath);
        loadProperties();
        songStore = new SongDataStore(CONFIG_DIR);
        migrateLegacySongData();
    }

    private void loadProperties() {
        if (!Files.exists(configFilePath)) {
            System.out.println("No se pudo cargar config.properties, se usarán valores por defecto.");
            return;
        }
        try (InputStream input = Files.newInputStream(configFilePath)) {
            props.load(input);
        } catch (IOException e) {
            System.out.println("No se pudo cargar config.properties, se usarán valores por defecto.");
        }
    }

    private void saveProperties() {
        ensureConfigDirectory();
        try (OutputStream output = Files.newOutputStream(configFilePath)) {
            props.store(output, "Configuración de OSU! Music Player");
        } catch (IOException e) {
            System.out.println("No se pudo guardar config.properties.");
        }
    }

    private void ensureConfigDirectory() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.out.println("No se pudo crear la carpeta de configuración en " + CONFIG_DIR.toAbsolutePath());
        }
    }

    private void migrateLegacyFile(Path legacyFile, Path targetFile) {
        if (!Files.exists(legacyFile) || Files.exists(targetFile)) {
            return;
        }
        try {
            Files.createDirectories(targetFile.getParent());
            Files.move(legacyFile, targetFile);
        } catch (IOException e) {
            System.out.println("No se pudo mover " + legacyFile + " a " + targetFile);
        }
    }

    public double getVolume() {
        return parseDouble(props.getProperty("volume", "0.5"), 0.5);
    }

    public void setVolume(double volume) {
        
        double clamped = Math.max(0d, Math.min(1.25d, volume));
        props.setProperty("volume", Double.toString(clamped));
        saveProperties();
    }

    public double getPreviewVolume() {
        return parseDouble(props.getProperty("osu.preview.volume", "0.7"), 0.7);
    }

    public void setPreviewVolume(double volume) {
        props.setProperty("osu.preview.volume", Double.toString(Math.max(0d, Math.min(1d, volume))));
        saveProperties();
    }

    public String getLastFolder() {
        return props.getProperty("lastFolder", "");
    }

    public void setLastFolder(String folderPath) {
        props.setProperty("lastFolder", folderPath);
        saveProperties();
    }

    public List<String> getFavorites() {
        return songStore.getFavorites();
    }

    public void setFavorites(List<String> favorites) {
        songStore.setFavorites(favorites);
    }

    public Map<String, List<String>> getPlaylists() {
        return songStore.getPlaylists();
    }

    public void setPlaylists(Map<String, List<String>> playlists) {
        songStore.setPlaylists(playlists);
    }

    public String getCurrentSong() {
        return songStore.getCurrentSong();
    }

    public double getCurrentSongPosition() {
        return songStore.getCurrentSongPosition();
    }

    public void setCurrentSong(String song, double position) {
        songStore.setCurrentSong(song, position);
    }

    public String getLastSong() {
        return songStore.getLastSong();
    }

    public void setLastSong(String songName) {
        songStore.setLastSong(songName);
    }

    public String getLastPlaylist() {
        return songStore.getLastPlaylist();
    }

    public void setLastPlaylist(String playlistName) {
        songStore.setLastPlaylist(playlistName);
    }

    public void setPlayHistory(List<String> history) {
        songStore.setPlayHistory(history);
    }

    public List<String> getPlayHistory() {
        return songStore.getPlayHistory();
    }

    public void setHistoryIndex(int index) {
        songStore.setHistoryIndex(index);
    }

    public int getHistoryIndex() {
        return songStore.getHistoryIndex();
    }

    public String getSongsDirectory() {
        return props.getProperty("songsDirectory", "");
    }

    public boolean isBeatmapDeleteConfirmationEnabled() {
        return Boolean.parseBoolean(props.getProperty("osu.delete.confirmation.enabled", "true"));
    }

    public void setBeatmapDeleteConfirmationEnabled(boolean enabled) {
        props.setProperty("osu.delete.confirmation.enabled", Boolean.toString(enabled));
        saveProperties();
    }

    public double getWindowWidth() {
        return parseDouble(props.getProperty("window.width", "1200"), 1200);
    }

    public void setWindowWidth(double width) {
        props.setProperty("window.width", Double.toString(width));
        saveProperties();
    }

    public double getWindowHeight() {
        return parseDouble(props.getProperty("window.height", "720"), 720);
    }

    public void setWindowHeight(double height) {
        props.setProperty("window.height", Double.toString(height));
        saveProperties();
    }
    
    

    
    public String getTheme() {
        
        return props.getProperty("theme", "dark");
    }

    
    public void setTheme(String themeId) {
        props.setProperty("theme", themeId);
        saveProperties();
    }

    public boolean isHistoryRetentionEnabled() {
        return Boolean.parseBoolean(props.getProperty("history.keepBetweenSessions", "false"));
    }

    public void setHistoryRetentionEnabled(boolean enabled) {
        props.setProperty("history.keepBetweenSessions", Boolean.toString(enabled));
        saveProperties();
        if (!enabled) {
            songStore.clearHistoryData();
        }
    }

    public void clearStoredHistory() {
        songStore.clearHistoryData();
    }

    public String getLanguage() {
        return props.getProperty("language");
    }

    public void setLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            props.remove("language");
        } else {
            props.setProperty("language", languageCode);
        }
        saveProperties();
    }

    public String getOsuClientId() {
        return props.getProperty("osu.clientId", "");
    }

    public void setOsuClientId(String clientId) {
        if (clientId == null) clientId = "";
        props.setProperty("osu.clientId", clientId.trim());
        saveProperties();
    }

    public String getOsuClientSecret() {
        return props.getProperty("osu.clientSecret", "");
    }

    public void setOsuClientSecret(String clientSecret) {
        if (clientSecret == null) clientSecret = "";
        props.setProperty("osu.clientSecret", clientSecret.trim());
        saveProperties();
    }

    public boolean isOsuDownloadWithVideo() {
        return Boolean.parseBoolean(props.getProperty("osu.download.includeVideo", "true"));
    }

    public void setOsuDownloadWithVideo(boolean includeVideo) {
        props.setProperty("osu.download.includeVideo", Boolean.toString(includeVideo));
        saveProperties();
    }

    public boolean isOsuAutoRefreshAfterImport() {
        return Boolean.parseBoolean(props.getProperty("osu.download.autoRefresh", "true"));
    }

    public void setOsuAutoRefreshAfterImport(boolean enabled) {
        props.setProperty("osu.download.autoRefresh", Boolean.toString(enabled));
        saveProperties();
    }

    public String getOsuUserAccessToken() {
        return props.getProperty("osu.user.accessToken", "");
    }

    public long getOsuUserAccessTokenExpiry() {
        return parseLong(props.getProperty("osu.user.accessTokenExpiry", "0"), 0L);
    }

    public String getOsuUserRefreshToken() {
        return props.getProperty("osu.user.refreshToken", "");
    }

    public void setOsuUserTokens(String accessToken, long expiryEpochSeconds, String refreshToken) {
        if (accessToken == null || accessToken.isBlank()) {
            props.remove("osu.user.accessToken");
            props.remove("osu.user.accessTokenExpiry");
            props.remove("osu.user.refreshToken");
        } else {
            props.setProperty("osu.user.accessToken", accessToken);
            props.setProperty("osu.user.accessTokenExpiry", Long.toString(Math.max(0, expiryEpochSeconds)));
            props.setProperty("osu.user.refreshToken", refreshToken == null ? "" : refreshToken);
        }
        saveProperties();
    }

    public void clearOsuUserTokens() {
        setOsuUserTokens(null, 0, null);
    }

    public String getShortcutBinding(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        return props.getProperty("shortcut." + actionId);
    }

    public void setShortcutBinding(String actionId, String combination) {
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        String key = "shortcut." + actionId;
        if (combination == null || combination.isBlank()) {
            props.remove(key);
        } else {
            props.setProperty(key, combination.trim());
        }
        saveProperties();
    }

    public void clearShortcutBinding(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        props.remove("shortcut." + actionId);
        saveProperties();
    }

    public SongLibraryCache loadSongLibraryCache() {
        return songStore.loadLibraryCache();
    }

    public void saveSongLibraryCache(SongLibraryCache cache) {
        songStore.saveLibraryCache(cache);
    }

    public void clearSongLibraryCache() {
        songStore.clearLibraryCache();
    }

    private double parseDouble(String val, double def) {
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String val, long def) {
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<String> parseList(String val, String sep) {
        if (val == null || val.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(val.split(sep)));
    }

    private void migrateLegacySongData() {
        boolean updatedConfig = false;

        if (props.containsKey("favorites")) {
            if (!songStore.hasKey("favorites")) {
                songStore.setFavorites(parseList(props.getProperty("favorites", ""), ","));
            }
            props.remove("favorites");
            updatedConfig = true;
        }

        Map<String, List<String>> legacyPlaylists = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("playlist.")) {
                String name = key.substring("playlist.".length());
                legacyPlaylists.put(name, parseList(props.getProperty(key, ""), ","));
            }
        }
        if (!legacyPlaylists.isEmpty()) {
            if (!songStore.hasAnyPlaylists()) {
                songStore.setPlaylists(legacyPlaylists);
            }
            props.keySet().removeIf(k -> k.toString().startsWith("playlist."));
            updatedConfig = true;
        }

        if (props.containsKey("currentSong") || props.containsKey("currentSongPosition")) {
            if (!songStore.hasKey("currentSong")) {
                String song = props.getProperty("currentSong", "");
                double position = parseDouble(props.getProperty("currentSongPosition", "0"), 0d);
                songStore.setCurrentSong(song, position);
            }
            props.remove("currentSong");
            props.remove("currentSongPosition");
            updatedConfig = true;
        }

        if (props.containsKey("lastSong")) {
            if (!songStore.hasKey("lastSong")) {
                songStore.setLastSong(props.getProperty("lastSong"));
            }
            props.remove("lastSong");
            updatedConfig = true;
        }

        if (props.containsKey("lastPlaylist")) {
            if (!songStore.hasKey("lastPlaylist")) {
                songStore.setLastPlaylist(props.getProperty("lastPlaylist"));
            }
            props.remove("lastPlaylist");
            updatedConfig = true;
        }

        if (props.containsKey("playHistory")) {
            if (!songStore.hasKey("playHistory")) {
                songStore.setPlayHistory(parseList(props.getProperty("playHistory", ""), ";"));
            }
            props.remove("playHistory");
            updatedConfig = true;
        }

        if (props.containsKey("historyIndex")) {
            if (!songStore.hasKey("historyIndex")) {
                int idx;
                try {
                    idx = Integer.parseInt(props.getProperty("historyIndex", "-1"));
                } catch (NumberFormatException e) {
                    idx = -1;
                }
                songStore.setHistoryIndex(idx);
            }
            props.remove("historyIndex");
            updatedConfig = true;
        }

        if (updatedConfig) {
            saveProperties();
        }
    }
}