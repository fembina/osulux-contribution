package com.osuplayer.playback;

import java.util.List;
import java.util.function.Consumer;

import com.osuplayer.exporting.ExportManager;
import com.osuplayer.favorites.FavoritesManager;
import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.ui.MetadataDialog;

import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public class SongListCell extends ListCell<String> {

    private final PlaylistManager playlistManager;
    private final FavoritesManager favoritesManager;
    private final ExportManager exportManager;
    private final MusicManager musicManager;
    private final Runnable refreshUICallback;
    private final TextFlow displayFlow = new TextFlow();
    private final Consumer<String> deleteBeatmapHandler;

    public SongListCell(PlaylistManager playlistManager, FavoritesManager favoritesManager,
                        ExportManager exportManager, MusicManager musicManager,
                        Runnable refreshUICallback, Consumer<String> deleteBeatmapHandler) {
        this.playlistManager = playlistManager;
        this.favoritesManager = favoritesManager;
        this.exportManager = exportManager;
        this.musicManager = musicManager;
        this.refreshUICallback = refreshUICallback;
        this.deleteBeatmapHandler = deleteBeatmapHandler;

        displayFlow.setMaxWidth(Double.MAX_VALUE);
        displayFlow.setMinWidth(0);
        displayFlow.setPrefWidth(Region.USE_COMPUTED_SIZE);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    @Override
    protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            setContextMenu(null);
            return;
        }

        setText(null);
        buildDisplayNode(item);
        setGraphic(displayFlow);
        setContextMenu(createSongContextMenu(item));
    }

    private void buildDisplayNode(String displayName) {
        displayFlow.getChildren().clear();
        MusicManager.SongDisplayParts displayParts = musicManager.getDisplayParts(displayName);
        if (displayParts != null) {
            renderFromDisplayParts(displayParts);
            return;
        }

        String workingName = displayName;
        MusicManager.SongMetadataDetails metadata = musicManager.getMetadata(displayName);
        String expectedDifficulty = normalizeMetadataValue(metadata == null ? null : metadata.difficulty);
        String expectedMapper = normalizeMetadataValue(metadata == null ? null : metadata.mapper);
        String duplicateSuffix = null;
        if (workingName.endsWith("]")) {
            int idx = workingName.lastIndexOf(" [");
            if (idx > 0) {
                duplicateSuffix = workingName.substring(idx);
                workingName = workingName.substring(0, idx);
            }
        }

        String mapperText = null;
        if (workingName.endsWith(")")) {
            int openParen = workingName.lastIndexOf(" (");
            if (openParen > 0 && openParen < workingName.length() - 2) {
                String candidate = workingName.substring(openParen + 2, workingName.length() - 1).trim();
                if (matchesExpectedSegment(candidate, expectedMapper)) {
                    mapperText = candidate;
                    workingName = workingName.substring(0, openParen).trim();
                }
            }
        }

        String baseText = workingName;
        String difficultyText = null;
        int slashIndex = workingName.lastIndexOf(" / ");
        if (slashIndex > 0 && slashIndex < workingName.length() - 3) {
            String candidate = workingName.substring(slashIndex + 3).trim();
            if (matchesExpectedSegment(candidate, expectedDifficulty)) {
                baseText = workingName.substring(0, slashIndex).trim();
                difficultyText = candidate;
            }
        }

        Text base = new Text(baseText);
        base.fillProperty().bind(textFillProperty());
        displayFlow.getChildren().add(base);

        if (difficultyText != null && !difficultyText.isEmpty()) {
            Text separator = new Text(" / ");
            separator.fillProperty().bind(textFillProperty());

            Text difficulty = new Text(difficultyText);
            difficulty.getStyleClass().add("song-difficulty");
            difficulty.fillProperty().bind(textFillProperty());

            displayFlow.getChildren().addAll(separator, difficulty);
        }

        if (mapperText != null && !mapperText.isEmpty()) {
            Text space = new Text(" ");
            space.fillProperty().bind(textFillProperty());

            Text mapper = new Text("(" + mapperText + ")");
            mapper.getStyleClass().add("song-mapper");
            mapper.fillProperty().bind(textFillProperty());

            displayFlow.getChildren().addAll(space, mapper);
        }

        if (duplicateSuffix != null) {
            Text suffix = new Text(" " + duplicateSuffix.trim());
            suffix.fillProperty().bind(textFillProperty());
            displayFlow.getChildren().add(suffix);
        }
    }

    private void renderFromDisplayParts(MusicManager.SongDisplayParts parts) {
        Text base = new Text(parts.baseText == null ? "" : parts.baseText);
        base.fillProperty().bind(textFillProperty());
        displayFlow.getChildren().add(base);

        if (parts.difficultyText != null) {
            Text separator = new Text(" / ");
            separator.fillProperty().bind(textFillProperty());
            Text diff = new Text(parts.difficultyText);
            diff.getStyleClass().add("song-difficulty");
            diff.fillProperty().bind(textFillProperty());
            displayFlow.getChildren().addAll(separator, diff);
        }

        if (parts.mapperText != null) {
            Text space = new Text(" ");
            space.fillProperty().bind(textFillProperty());
            Text mapper = new Text("(" + parts.mapperText + ")");
            mapper.getStyleClass().add("song-mapper");
            mapper.fillProperty().bind(textFillProperty());
            displayFlow.getChildren().addAll(space, mapper);
        }

        if (parts.duplicateSuffix != null) {
            Text space = new Text(" ");
            space.fillProperty().bind(textFillProperty());
            Text suffix = new Text(parts.duplicateSuffix);
            suffix.fillProperty().bind(textFillProperty());
            displayFlow.getChildren().addAll(space, suffix);
        }
    }

    private String normalizeMetadataValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean matchesExpectedSegment(String candidate, String expected) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        return candidate.equals(expected);
    }

    private ContextMenu createSongContextMenu(String song) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem addToQueueItem = new MenuItem("Añadir a la cola");
        LanguageBindings.bindMenuItem(addToQueueItem, "Añadir a la cola");
        addToQueueItem.setOnAction(e -> {
            playlistManager.addToPlaylist(PlaylistManager.PLAYLIST_QUEUE, song);
            refreshUICallback.run();
        });
        contextMenu.getItems().add(addToQueueItem);
        
        Menu addToPlaylistMenu = new Menu("Añadir a playlist");
        LanguageBindings.bindMenuItem(addToPlaylistMenu, "Añadir a playlist");
        MenuItem addFavItem = new MenuItem("Favoritos");
        LanguageBindings.bindMenuItem(addFavItem, "Favoritos");
        addFavItem.setOnAction(e -> {
            favoritesManager.addFavorite(song);
            refreshUICallback.run();
        });
        addToPlaylistMenu.getItems().add(addFavItem);

        for (String playlistName : playlistManager.getAllPlaylists()) {
            if (!playlistManager.isSpecialPlaylist(playlistName)) {
                MenuItem playlistItem = new MenuItem(playlistName);
                LanguageBindings.bindMenuItem(playlistItem, playlistName);
                playlistItem.setOnAction(ev -> {
                    playlistManager.addToPlaylist(playlistName, song);
                    refreshUICallback.run();
                });
                addToPlaylistMenu.getItems().add(playlistItem);
            }
        }

        Menu removeFromPlaylistMenu = new Menu("Eliminar de");
        LanguageBindings.bindMenuItem(removeFromPlaylistMenu, "Eliminar de");
        boolean inAnyPlaylist = false;
        for (String playlistName : playlistManager.getAllPlaylists()) {
            if (!playlistName.equalsIgnoreCase(PlaylistManager.PLAYLIST_ALL)) {
                List<String> list = playlistManager.getPlaylist(playlistName);
                if (list != null && list.contains(song)) {
                    inAnyPlaylist = true;
                    MenuItem removeFromItem = new MenuItem(playlistName);
                    LanguageBindings.bindMenuItem(removeFromItem, playlistName);
                    removeFromItem.setOnAction(e -> {
                        if (playlistName.equals(PlaylistManager.PLAYLIST_FAVORITES)) {
                            favoritesManager.removeFavorite(song);
                        } else {
                            playlistManager.removeFromPlaylist(playlistName, song);
                        }
                        refreshUICallback.run();
                    });
                    removeFromPlaylistMenu.getItems().add(removeFromItem);
                }
            }
        }

        MenuItem exportSongItem = new MenuItem("Exportar canción");
        LanguageBindings.bindMenuItem(exportSongItem, "Exportar canción");
        exportSongItem.setOnAction(e -> exportManager.exportSong(song));

        MenuItem metadataItem = new MenuItem("Mostrar metadata");
        LanguageBindings.bindMenuItem(metadataItem, "Mostrar metadata");
        metadataItem.setOnAction(e -> {
            MetadataDialog.show(song, musicManager.getMetadata(song),
                getListView() == null ? null : getListView().getScene());
        });

        contextMenu.getItems().add(addToPlaylistMenu);
        if (inAnyPlaylist) {
            contextMenu.getItems().add(removeFromPlaylistMenu);
        }
        contextMenu.getItems().add(exportSongItem);

        if (deleteBeatmapHandler != null) {
            MenuItem deleteBeatmapItem = new MenuItem("Eliminar beatmap");
            LanguageBindings.bindMenuItem(deleteBeatmapItem, "Eliminar beatmap");
            deleteBeatmapItem.setOnAction(e -> deleteBeatmapHandler.accept(song));
            contextMenu.getItems().add(deleteBeatmapItem);
        }

        contextMenu.getItems().add(metadataItem);

        return contextMenu;
    }
}