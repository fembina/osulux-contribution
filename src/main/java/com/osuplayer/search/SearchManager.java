package com.osuplayer.search;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.osuplayer.playback.MusicManager;
import com.osuplayer.playback.MusicManager.SongMetadataDetails;

import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

public class SearchManager {

    private final MusicManager musicManager;
    private FilteredList<String> filteredSongList;

    public SearchManager(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    public void setFilteredList(FilteredList<String> filteredSongList) {
        this.filteredSongList = filteredSongList;
    }

    public void setupSearchField(
            TextField searchField,
            ListView<String> songListView,
            Label currentSongLabel) {
        
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (filteredSongList == null) return;

            String lower = newVal == null ? "" : newVal.toLowerCase().trim();
            List<String> tokens = tokenize(lower);

            filteredSongList.setPredicate(song -> {
                if (tokens.isEmpty()) return true;
                return song != null && matchesQueryTokens(song, tokens);
            });

            String currentSong = currentSongLabel.getText();
            if (currentSong != null && !currentSong.isEmpty()) {
                int index = filteredSongList.indexOf(currentSong);
                if (index >= 0) {
                    songListView.getSelectionModel().select(index);
                    songListView.scrollTo(index);
                } else {
                    songListView.getSelectionModel().clearSelection();
                }
            }
        });
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        return Arrays.stream(query.split("\\s+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    public boolean matchesQueryTokens(String songName, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return true;
        if (songName == null) return false;

        String lowerCaseSong = songName.toLowerCase();
        SongMetadataDetails metadata = musicManager.getMetadata(songName);
        List<String> creators = musicManager.getCreators(songName);
        List<String> tags = musicManager.getTags(songName);

        for (String token : tokens) {
            if (token.isBlank()) continue;

            boolean tokenMatched = lowerCaseSong.contains(token);

            if (!tokenMatched && metadata != null) {
                tokenMatched = (metadata.title != null && metadata.title.toLowerCase().contains(token))
                    || (metadata.artist != null && metadata.artist.toLowerCase().contains(token))
                    || (metadata.mapper != null && metadata.mapper.toLowerCase().contains(token))
                    || (metadata.difficulty != null && metadata.difficulty.toLowerCase().contains(token))
                    || (metadata.source != null && metadata.source.toLowerCase().contains(token));
            }

            if (!tokenMatched && creators != null) {
                tokenMatched = creators.stream()
                    .filter(c -> c != null)
                    .anyMatch(c -> c.toLowerCase().contains(token));
            }

            if (!tokenMatched && tags != null) {
                tokenMatched = tags.stream()
                    .filter(t -> t != null)
                    .anyMatch(t -> t.toLowerCase().contains(token));
            }

            if (!tokenMatched && metadata != null && metadata.tags != null) {
                tokenMatched = metadata.tags.stream()
                    .filter(t -> t != null)
                    .anyMatch(t -> t.toLowerCase().contains(token));
            }

            if (!tokenMatched) {
                return false;
            }
        }

        return true;
    }
}