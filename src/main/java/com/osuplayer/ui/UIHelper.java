package com.osuplayer.ui;

import java.io.File;
import java.util.function.Consumer;

import com.osuplayer.lang.I18n;
import com.osuplayer.lang.LanguageBindings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

public final class UIHelper {

    private UIHelper() {}

    public record TopBarComponents(HBox bar, TextField searchField) {}
    public record ControlBarComponents(VBox bar, Slider progressSlider, Label timeLabel, Slider volumeSlider, Button previousButton, Button playPauseButton, Button stopButton, Button nextButton, Button shuffleButton, Button loopButton, Label speedLabel) {}

    public static TopBarComponents createTopBar(Stage ownerStage,
                                                Consumer<File> onFolderChosen,
                                                Runnable onDownloadRequested,
                                                Runnable onShortcutPreferencesRequested,
                                                Runnable onSettingsRequested,
                                                Runnable onUpdateRequested,
                                                Runnable onAboutRequested) {
        MenuItem changeFolderItem = new MenuItem();
        LanguageBindings.bindMenuItem(changeFolderItem, "Cambiar carpeta de canciones");
        changeFolderItem.setOnAction(e -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle(I18n.tr("Selecciona la carpeta de canciones de OSU!"));
            String userHome = System.getProperty("user.home");
            File defaultDir = new File(userHome, "AppData/Local/osu!/Songs");
            if (defaultDir.exists() && defaultDir.isDirectory()) {
                directoryChooser.setInitialDirectory(defaultDir);
            }
            File selectedDirectory = directoryChooser.showDialog(ownerStage);
            if (selectedDirectory != null) {
                onFolderChosen.accept(selectedDirectory);
            }
        });

        MenuItem downloadBeatmapsItem = new MenuItem();
        LanguageBindings.bindMenuItem(downloadBeatmapsItem, "Buscar/descargar beatmaps");
        downloadBeatmapsItem.setOnAction(e -> {
            if (onDownloadRequested != null) {
                onDownloadRequested.run();
            }
        });

        MenuItem shortcutsItem = new MenuItem();
        LanguageBindings.bindMenuItem(shortcutsItem, "Atajos de teclado");
        shortcutsItem.setOnAction(e -> {
            if (onShortcutPreferencesRequested != null) {
                onShortcutPreferencesRequested.run();
            }
        });

        MenuItem settingsItem = new MenuItem();
        LanguageBindings.bindMenuItem(settingsItem, "Configuración");
        settingsItem.setOnAction(e -> {
            if (onSettingsRequested != null) {
                onSettingsRequested.run();
            }
        });

        MenuItem updateItem = new MenuItem();
        LanguageBindings.bindMenuItem(updateItem, "Buscar actualizaciones");
        updateItem.setOnAction(e -> {
            if (onUpdateRequested != null) {
                onUpdateRequested.run();
            }
        });

        MenuItem aboutItem = new MenuItem();
        LanguageBindings.bindMenuItem(aboutItem, "Acerca de Osulux");
        aboutItem.setOnAction(e -> {
            if (onAboutRequested != null) {
                onAboutRequested.run();
            }
        });

        Menu optionsMenu = new Menu();
        LanguageBindings.bindMenuItem(optionsMenu, "Opciones");
        optionsMenu.setStyle("-fx-font-weight: bold;");

        MenuItem[] orderedItems = {
            settingsItem,
            downloadBeatmapsItem,
            shortcutsItem,
            updateItem,
            changeFolderItem,
            aboutItem
        };

        for (int i = 0; i < orderedItems.length; i++) {
            optionsMenu.getItems().add(orderedItems[i]);
            if (i < orderedItems.length - 1) {
                optionsMenu.getItems().add(new SeparatorMenuItem());
            }
        }

        MenuButton optionsButton = new MenuButton();
        LanguageBindings.bindLabeled(optionsButton, "Opciones");
        optionsButton.getItems().setAll(optionsMenu.getItems());
        optionsButton.setFocusTraversable(false);
        optionsButton.setStyle("-fx-font-weight: bold; -fx-padding: 6 14; -fx-background-radius: 6;");

        TextField searchField = new TextField();
        LanguageBindings.bindPrompt(searchField, "Buscar canciones, artistas, creadores o tags...");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox topBox = new HBox(10, optionsButton, searchField);
        topBox.setPadding(new Insets(10));
        topBox.setAlignment(Pos.CENTER_LEFT);

        return new TopBarComponents(topBox, searchField);
    }

    public static BorderPane createMediaPanel(StackPane mediaDisplayStack, Label currentSongLabel, Button favoriteButton) {
        currentSongLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        currentSongLabel.setWrapText(true);
        currentSongLabel.setAlignment(Pos.CENTER);
        currentSongLabel.setMaxWidth(Double.MAX_VALUE);

        VBox titleFavoriteBox = new VBox(5);
        titleFavoriteBox.setAlignment(Pos.CENTER);
        titleFavoriteBox.setFillWidth(true);
        titleFavoriteBox.getChildren().addAll(currentSongLabel, favoriteButton);

        BorderPane mediaContainer = new BorderPane();
        mediaContainer.setPadding(new Insets(10));
        mediaContainer.setCenter(mediaDisplayStack);
        mediaContainer.setBottom(titleFavoriteBox);
        BorderPane.setAlignment(titleFavoriteBox, Pos.CENTER);

        return mediaContainer;
    }

    public static ControlBarComponents createControlBar() {
        Slider progressSlider = new Slider(0, 0, 0);
        progressSlider.setFocusTraversable(false);
        Label timeLabel = new Label("00:00 / 00:00");
        timeLabel.setAlignment(Pos.CENTER_RIGHT);
        timeLabel.setMaxWidth(Double.MAX_VALUE);
        Slider volumeSlider = new Slider(0, 125, 50);
        volumeSlider.setFocusTraversable(false);
        Button previousButton = createControlButton("⏮");
        Button playPauseButton = createControlButton("▶");
        Button stopButton = createControlButton("⏹");
        Button nextButton = createControlButton("⏭");
        Button shuffleButton = createControlButton("🔀");
        Button loopButton = createControlButton("🔁");
        Label speedLabel = new Label();
        speedLabel.setManaged(true);
        speedLabel.setMinWidth(Region.USE_PREF_SIZE);
        speedLabel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        speedLabel.setMaxWidth(Region.USE_PREF_SIZE);
        speedLabel.setAlignment(Pos.CENTER_RIGHT);
        speedLabel.setMouseTransparent(true);
        speedLabel.setOpacity(0);
        speedLabel.setStyle("-fx-font-size: 11px; -fx-font-style: italic; -fx-text-fill: white; -fx-padding: 1 8 1 8; -fx-background-color: rgba(0,0,0,0.28); -fx-background-radius: 8; -fx-border-radius: 8;");

        HBox controlGroup = new HBox(10, previousButton, playPauseButton, stopButton, nextButton, shuffleButton, loopButton, volumeSlider);
        controlGroup.setAlignment(Pos.CENTER);

        StackPane controlBox = new StackPane();
        controlBox.setPadding(new Insets(5, 10, 5, 10));
        controlBox.getChildren().addAll(controlGroup, speedLabel);
        StackPane.setAlignment(controlGroup, Pos.CENTER);
        StackPane.setAlignment(speedLabel, Pos.CENTER_RIGHT);

        HBox progressBox = new HBox(10, progressSlider, timeLabel);
        progressBox.setAlignment(Pos.CENTER);
        progressBox.setPadding(new Insets(0, 10, 5, 10));
        HBox.setHgrow(progressSlider, Priority.ALWAYS);

        VBox bottomBox = new VBox(controlBox, progressBox);

        return new ControlBarComponents(bottomBox, progressSlider, timeLabel, volumeSlider, previousButton, playPauseButton, stopButton, nextButton, shuffleButton, loopButton, speedLabel);
    }

    private static Button createControlButton(String text) {
        Button btn = new Button(text);
        btn.setPrefWidth(40);
        btn.setPrefHeight(30);
        btn.setFocusTraversable(false);
        return btn;
    }
}