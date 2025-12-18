package com.osuplayer.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

final class SongDataStore {

    private static final String SONGS_FILE = "songs.properties";
    private static final String COMMENT = "Datos de canciones de Osulux";
    private static final String PLAYLIST_PREFIX = "playlist.";
    private static final String CACHE_KEY = "library.cache";
    private static final Gson GSON = new Gson();

    private final Properties props = new Properties();
    private final Path songsFile;
    private final Path configDir;

    SongDataStore(Path configDir) {
        this.configDir = configDir;
        this.songsFile = configDir.resolve(SONGS_FILE);
        ensureConfigDirectory();
        migrateLegacyFile();
        load();
    }

    private void load() {
        if (!Files.exists(songsFile)) {
            return;
        }
        try (InputStream input = Files.newInputStream(songsFile)) {
            props.load(input);
        } catch (IOException ignored) {
        }
    }

    private void save() {
        ensureConfigDirectory();
        try (OutputStream output = Files.newOutputStream(songsFile)) {
            props.store(output, COMMENT);
        } catch (IOException e) {
            System.out.println("No se pudo guardar songs.properties.");
        }
    }

    private void ensureConfigDirectory() {
        try {
            Files.createDirectories(configDir);
        } catch (IOException ignored) {
        }
    }

    private void migrateLegacyFile() {
        Path legacy = Paths.get(SONGS_FILE);
        if (!Files.exists(legacy) || Files.exists(songsFile)) {
            return;
        }
        try {
            Files.createDirectories(configDir);
            Files.move(legacy, songsFile);
        } catch (IOException ignored) {
        }
    }

    boolean hasKey(String key) {
        return props.containsKey(key);
    }

    boolean hasAnyPlaylists() {
        Set<String> names = props.stringPropertyNames();
        for (String name : names) {
            if (name.startsWith(PLAYLIST_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    List<String> getFavorites() {
        return parseList(props.getProperty("favorites", ""), ",");
    }

    void setFavorites(List<String> favorites) {
        props.setProperty("favorites", joinList(favorites, ","));
        save();
    }

    Map<String, List<String>> getPlaylists() {
        Map<String, List<String>> playlists = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(PLAYLIST_PREFIX)) {
                String name = key.substring(PLAYLIST_PREFIX.length());
                playlists.put(name, parseList(props.getProperty(key, ""), ","));
            }
        }
        return playlists;
    }

    void setPlaylists(Map<String, List<String>> playlists) {
        props.keySet().removeIf(key -> key.toString().startsWith(PLAYLIST_PREFIX));
        if (playlists != null) {
            for (Map.Entry<String, List<String>> entry : playlists.entrySet()) {
                props.setProperty(PLAYLIST_PREFIX + entry.getKey(), joinList(entry.getValue(), ","));
            }
        }
        save();
    }

    String getCurrentSong() {
        return props.getProperty("currentSong", "");
    }

    double getCurrentSongPosition() {
        return parseDouble(props.getProperty("currentSongPosition", "0"), 0d);
    }

    void setCurrentSong(String song, double position) {
        props.setProperty("currentSong", song == null ? "" : song);
        props.setProperty("currentSongPosition", Double.toString(position));
        save();
    }

    String getLastSong() {
        return props.getProperty("lastSong", "");
    }

    void setLastSong(String songName) {
        props.setProperty("lastSong", songName == null ? "" : songName);
        save();
    }

    String getLastPlaylist() {
        return props.getProperty("lastPlaylist", "Todo");
    }

    void setLastPlaylist(String playlistName) {
        if (playlistName == null || playlistName.isBlank()) {
            props.remove("lastPlaylist");
        } else {
            props.setProperty("lastPlaylist", playlistName);
        }
        save();
    }

    void setPlayHistory(List<String> history) {
        props.setProperty("playHistory", joinList(history, ";"));
        save();
    }

    List<String> getPlayHistory() {
        return parseList(props.getProperty("playHistory", ""), ";");
    }

    void setHistoryIndex(int index) {
        props.setProperty("historyIndex", Integer.toString(index));
        save();
    }

    int getHistoryIndex() {
        try {
            return Integer.parseInt(props.getProperty("historyIndex", "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void clearHistoryData() {
        props.remove("playHistory");
        props.remove("historyIndex");
        save();
    }

    SongLibraryCache loadLibraryCache() {
        String json = props.getProperty(CACHE_KEY);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            SongLibraryCache cache = GSON.fromJson(json, SongLibraryCache.class);
            if (cache == null || cache.version() != SongLibraryCache.FORMAT_VERSION) {
                return null;
            }
            return cache;
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    void saveLibraryCache(SongLibraryCache cache) {
        if (cache == null) {
            props.remove(CACHE_KEY);
        } else {
            props.setProperty(CACHE_KEY, GSON.toJson(cache));
        }
        save();
    }

    void clearLibraryCache() {
        props.remove(CACHE_KEY);
        save();
    }

    private List<String> parseList(String val, String separator) {
        if (val == null || val.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(val.split(separator)));
    }

    private String joinList(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(separator, values);
    }

    private double parseDouble(String val, double def) {
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
