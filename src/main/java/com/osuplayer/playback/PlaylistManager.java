package com.osuplayer.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.osuplayer.config.ConfigManager;

public class PlaylistManager {

    public static final String PLAYLIST_ALL = "Todo";
    public static final String PLAYLIST_FAVORITES = "Favoritos";
    public static final String PLAYLIST_HISTORY = "Historial";
    public static final String PLAYLIST_QUEUE = "Cola";

    private final ConfigManager configManager;
    private final Map<String, List<String>> playlists;
    private final Set<String> specialPlaylists = Set.of(PLAYLIST_ALL, PLAYLIST_FAVORITES, PLAYLIST_HISTORY, PLAYLIST_QUEUE);

    public PlaylistManager(ConfigManager configManager) {
        this.configManager = configManager;
        this.playlists = new LinkedHashMap<>();
        loadPlaylists();
    }

    private void loadPlaylists() {
        Map<String, List<String>> loaded = configManager.getPlaylists();
        if (loaded != null) {
            playlists.putAll(loaded);
        }
        ensureSpecialPresence();
        reorderSpecialPlaylists();
        savePlaylists();
    }

    private void ensureSpecialPresence() {
        playlists.putIfAbsent(PLAYLIST_QUEUE, new ArrayList<>());
        playlists.putIfAbsent(PLAYLIST_ALL, new ArrayList<>());
        playlists.putIfAbsent(PLAYLIST_FAVORITES, new ArrayList<>());
        playlists.putIfAbsent(PLAYLIST_HISTORY, new ArrayList<>());
    }

    private void reorderSpecialPlaylists() {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        List<String> specialOrder = List.of(PLAYLIST_ALL, PLAYLIST_FAVORITES, PLAYLIST_HISTORY, PLAYLIST_QUEUE);
        for (String special : specialOrder) {
            List<String> values = playlists.get(special);
            if (values != null) {
                ordered.put(special, values);
            }
        }
        for (Map.Entry<String, List<String>> entry : playlists.entrySet()) {
            if (!specialPlaylists.contains(entry.getKey())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        playlists.clear();
        playlists.putAll(ordered);
    }

    public boolean isSpecialPlaylist(String name) {
        return specialPlaylists.contains(name);
    }

    public boolean createPlaylist(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || playlists.containsKey(trimmed) || isSpecialPlaylist(trimmed)) {
            return false;
        }
        playlists.put(trimmed, new ArrayList<>());
        savePlaylists();
        return true;
    }

    public void deletePlaylist(String name) {
        if (!isSpecialPlaylist(name)) {
            playlists.remove(name);
            savePlaylists();
        }
    }

    public void addToPlaylist(String playlist, String song) {
        if (song == null || song.isBlank()) {
            return;
        }
        playlists.computeIfAbsent(playlist, k -> new ArrayList<>());
        List<String> songs = playlists.get(playlist);
        if (isQueuePlaylist(playlist) || !songs.contains(song)) {
            songs.add(song);
            savePlaylists();
        }
    }

    public void removeFromPlaylist(String playlist, String song) {
        if (playlists.containsKey(playlist)) {
            playlists.get(playlist).remove(song);
            savePlaylists();
        }
    }

    public List<String> getPlaylist(String name) {
        return playlists.getOrDefault(name, Collections.emptyList());
    }

    public Set<String> getAllPlaylists() {
        return playlists.keySet();
    }

    public Map<String, List<String>> getPlaylistsAsMap() {
        return Collections.unmodifiableMap(playlists);
    }

    public boolean isQueuePlaylist(String name) {
        return name != null && name.equalsIgnoreCase(PLAYLIST_QUEUE);
    }

    public String pollQueue() {
        List<String> queue = playlists.get(PLAYLIST_QUEUE);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        String next = queue.remove(0);
        savePlaylists();
        return next;
    }

    public boolean hasQueuedSongs() {
        List<String> queue = playlists.get(PLAYLIST_QUEUE);
        return queue != null && !queue.isEmpty();
    }

    public void pruneQueue(Set<String> validSongs) {
        List<String> queue = playlists.get(PLAYLIST_QUEUE);
        if (queue == null) {
            return;
        }
        if (queue.removeIf(song -> !validSongs.contains(song))) {
            savePlaylists();
        }
    }

    public void setPlaylistSongs(String playlist, List<String> songs) {
        if (playlist != null) {
            playlists.put(playlist, new ArrayList<>(songs));
            savePlaylists();
        }
    }

    public void savePlaylists() {
        configManager.setPlaylists(playlists);
    }

    public void removeSongsFromAllPlaylists(List<String> songsToRemove) {
        if (songsToRemove == null || songsToRemove.isEmpty()) {
            return;
        }
        boolean updated = false;
        for (List<String> playlistSongs : playlists.values()) {
            if (playlistSongs.removeAll(songsToRemove)) {
                updated = true;
            }
        }
        if (updated) {
            savePlaylists();
        }
    }
}