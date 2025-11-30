package com.osuplayer;

import java.util.List;

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

    public SongListCell(PlaylistManager playlistManager, FavoritesManager favoritesManager,
                        ExportManager exportManager, MusicManager musicManager,
                        Runnable refreshUICallback) {
        this.playlistManager = playlistManager;
        this.favoritesManager = favoritesManager;
        this.exportManager = exportManager;
        this.musicManager = musicManager;
        this.refreshUICallback = refreshUICallback;

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
        String workingName = displayName;
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
                mapperText = workingName.substring(openParen + 2, workingName.length() - 1).trim();
                workingName = workingName.substring(0, openParen).trim();
            }
        }

        String baseText = workingName;
        String difficultyText = null;
        int slashIndex = workingName.lastIndexOf(" / ");
        if (slashIndex > 0 && slashIndex < workingName.length() - 3) {
            baseText = workingName.substring(0, slashIndex).trim();
            difficultyText = workingName.substring(slashIndex + 3).trim();
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

    private ContextMenu createSongContextMenu(String song) {
        ContextMenu contextMenu = new ContextMenu();
        
        Menu addToPlaylistMenu = new Menu("Añadir a playlist");
        MenuItem addFavItem = new MenuItem("Favoritos");
        addFavItem.setOnAction(e -> {
            favoritesManager.addFavorite(song);
            refreshUICallback.run();
        });
        addToPlaylistMenu.getItems().add(addFavItem);

        for (String playlistName : playlistManager.getAllPlaylists()) {
            if (!playlistManager.isSpecialPlaylist(playlistName)) {
                MenuItem playlistItem = new MenuItem(playlistName);
                playlistItem.setOnAction(ev -> {
                    playlistManager.addToPlaylist(playlistName, song);
                    refreshUICallback.run();
                });
                addToPlaylistMenu.getItems().add(playlistItem);
            }
        }

        Menu removeFromPlaylistMenu = new Menu("Eliminar de");
        boolean inAnyPlaylist = false;
        for (String playlistName : playlistManager.getAllPlaylists()) {
            if (!playlistName.equalsIgnoreCase("Todo")) {
                List<String> list = playlistManager.getPlaylist(playlistName);
                if (list != null && list.contains(song)) {
                    inAnyPlaylist = true;
                    MenuItem removeFromItem = new MenuItem(playlistName);
                    removeFromItem.setOnAction(e -> {
                        if (playlistName.equals("Favoritos")) {
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
        exportSongItem.setOnAction(e -> exportManager.exportSong(song));

        MenuItem metadataItem = new MenuItem("Mostrar metadata");
        metadataItem.setOnAction(e -> {
            MetadataDialog.show(song, musicManager.getMetadata(song),
                getListView() == null ? null : getListView().getScene());
        });

        contextMenu.getItems().add(addToPlaylistMenu);
        if (inAnyPlaylist) {
            contextMenu.getItems().add(removeFromPlaylistMenu);
        }
        contextMenu.getItems().addAll(exportSongItem, metadataItem);

        return contextMenu;
    }
}