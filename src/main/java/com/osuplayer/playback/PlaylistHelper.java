package com.osuplayer.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.osuplayer.exporting.ExportManager;
import com.osuplayer.lang.I18n;
import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.lang.LanguageManager;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane; 
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class PlaylistHelper {

    private final PlaylistManager playlistManager;
    private final ExportManager exportManager;
    private final LanguageManager languageManager = LanguageManager.getInstance();

    private final ListView<String> playlistListView;
    private final Button newPlaylistButton;
    private final VBox playlistPane;

    private Consumer<String> onPlaylistSelectedCallback;
    private Runnable onPlaylistsChangedCallback;
    private boolean blockSelectionListener = false;

    
    
    private ObservableList<String> parentStylesheets;

    public PlaylistHelper(PlaylistManager playlistManager, ExportManager exportManager) {
        this.playlistManager = playlistManager;
        this.exportManager = exportManager;

        this.playlistListView = new ListView<>();
        this.playlistListView.setFixedCellSize(28);
        this.newPlaylistButton = new Button();
        LanguageBindings.bindLabeled(newPlaylistButton, "Nueva playlist");
        newPlaylistButton.setFocusTraversable(false);

        this.playlistPane = new VBox(5, playlistListView, newPlaylistButton);
        VBox.setVgrow(playlistListView, Priority.ALWAYS);
        newPlaylistButton.setMaxWidth(Double.MAX_VALUE);

        languageManager.languageIdProperty().addListener((obs, oldId, newId) -> playlistListView.refresh());
    }

    public void setOnPlaylistsChangedCallback(Runnable onPlaylistsChangedCallback) {
        this.onPlaylistsChangedCallback = onPlaylistsChangedCallback;
    }

    
    
    public VBox initialize(Consumer<String> onPlaylistSelectedCallback, ObservableList<String> parentStylesheets) {
        this.onPlaylistSelectedCallback = onPlaylistSelectedCallback;
        this.parentStylesheets = parentStylesheets; 
        setupListView();
        setupButtonActions();
        refreshPlaylistList();
        return playlistPane;
    }

    private void setupListView() {
        playlistListView.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (blockSelectionListener) return;

            if (newSelection != null && onPlaylistSelectedCallback != null) {
                onPlaylistSelectedCallback.accept(newSelection);
            }
        });

        playlistListView.setCellFactory(lv -> {
            ListCell<String> cell = new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {
                        setText(null);
                        setContextMenu(null);
                        setStyle("");
                    } else {
                        setText(translatePlaylistName(item));
                        if (playlistManager.isSpecialPlaylist(item)) {
                            setStyle("-fx-font-weight: bold;");
                        } else {
                            setStyle("");
                        }
                        setContextMenu(createPlaylistContextMenu(item));
                    }
                }
            };

            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() == MouseButton.SECONDARY && !cell.isEmpty()) {
                    blockSelectionListener = true;
                    playlistListView.getSelectionModel().select(cell.getIndex());
                    blockSelectionListener = false;
                }
            });

            return cell;
        });
    }

    private ContextMenu createPlaylistContextMenu(String playlistName) {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem exportAllItem = new MenuItem();
        LanguageBindings.bindMenuItem(exportAllItem, "Exportar todas las canciones");
        exportAllItem.setOnAction(e -> {
            exportManager.exportPlaylist(playlistName, playlistManager.getPlaylistsAsMap());
        });
        contextMenu.getItems().add(exportAllItem);

        if (!playlistManager.isSpecialPlaylist(playlistName)) {
            MenuItem deleteItem = new MenuItem();
            LanguageBindings.bindMenuItem(deleteItem, "Eliminar playlist");
            deleteItem.setOnAction(e -> {
                playlistManager.deletePlaylist(playlistName);
                refreshPlaylistList();
                if (onPlaylistsChangedCallback != null) onPlaylistsChangedCallback.run();
                if (onPlaylistSelectedCallback != null) {
                    onPlaylistSelectedCallback.accept(PlaylistManager.PLAYLIST_ALL);
                }
            });
            contextMenu.getItems().add(deleteItem);
        }
        
        return contextMenu;
    }

    private void setupButtonActions() {
        newPlaylistButton.setOnAction(e -> createNewPlaylist());
    }

    private void createNewPlaylist() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.tr("Nueva playlist"));
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.tr("Nombre de la playlist:"));
        
        
        
        applyStylesToDialog(dialog.getDialogPane());

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (playlistManager.createPlaylist(name)) {
                refreshPlaylistList();
                if (onPlaylistsChangedCallback != null) onPlaylistsChangedCallback.run();
            } else {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.tr("Nombre inválido"));
                alert.setHeaderText(null);
                alert.setContentText(I18n.tr("El nombre de la playlist no es válido o ya existe."));
                
                
                applyStylesToDialog(alert.getDialogPane());
                
                alert.showAndWait();
            }
        });
    }

    
    
    private void applyStylesToDialog(DialogPane dialogPane) {
        if (this.parentStylesheets != null) {
            dialogPane.getStylesheets().addAll(this.parentStylesheets);
        }
    }

    public void refreshPlaylistList() {
        String selected = getSelectedPlaylist();
        ObservableList<String> items = FXCollections.observableArrayList();
        if (playlistManager.getAllPlaylists().contains(PlaylistManager.PLAYLIST_ALL)) {
            items.add(PlaylistManager.PLAYLIST_ALL);
        }
        if (playlistManager.getAllPlaylists().contains(PlaylistManager.PLAYLIST_FAVORITES)) {
            items.add(PlaylistManager.PLAYLIST_FAVORITES);
        }
        if (playlistManager.getAllPlaylists().contains(PlaylistManager.PLAYLIST_HISTORY)) {
            items.add(PlaylistManager.PLAYLIST_HISTORY);
        }
        if (playlistManager.getAllPlaylists().contains(PlaylistManager.PLAYLIST_QUEUE)) {
            items.add(PlaylistManager.PLAYLIST_QUEUE);
        }

        List<String> sortedUserPlaylists = new ArrayList<>();
        for (String key : playlistManager.getAllPlaylists()) {
            if (!playlistManager.isSpecialPlaylist(key)) {
                sortedUserPlaylists.add(key);
            }
        }
        sortedUserPlaylists.sort(String.CASE_INSENSITIVE_ORDER);
        items.addAll(sortedUserPlaylists);

        playlistListView.setItems(items);
        if (selected != null && items.contains(selected)) {
            selectPlaylist(selected);
        }
    }

    public void selectPlaylist(String playlistName) {
        playlistListView.getSelectionModel().select(playlistName);
    }

    public String getSelectedPlaylist() {
        return playlistListView.getSelectionModel().getSelectedItem();
    }

    private String translatePlaylistName(String playlistName) {
        if (playlistName == null) {
            return "";
        }
        return playlistManager.isSpecialPlaylist(playlistName) ? I18n.tr(playlistName) : playlistName;
    }
}