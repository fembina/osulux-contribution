package com.osuplayer.lang;

import com.osuplayer.ui.ThemeHelper;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;


public final class LanguageSelectionDialog {

    private final Stage stage = new Stage();
    private final ToggleGroup languageGroup = new ToggleGroup();
    private final Button continueButton = new Button("Siguiente / Next");
    private String selectedLanguage;

    public LanguageSelectionDialog(Window owner, String themeId) {
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner instanceof Stage ownerStage) {
            stage.initOwner(ownerStage);
            stage.getIcons().setAll(ownerStage.getIcons());
        } else if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setResizable(false);
        stage.setTitle("Elige tu idioma / Choose your language");

        VBox root = new VBox(18);
        root.setPadding(new Insets(20));

        Label title = new Label("Elige tu idioma / Choose your language");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label subtitle = new Label("Puedes cambiarlo más tarde en Configuración / You can change it later in Settings");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-opacity: 0.9;");

        HBox buttonRow = new HBox(12);
        buttonRow.setAlignment(Pos.CENTER);
        buttonRow.setPadding(new Insets(12, 0, 0, 0));

        LanguageManager manager = LanguageManager.getInstance();
        ToggleButton spanishButton = createLanguageButton(findPack(manager, "es"), "Español", "es");
        ToggleButton englishButton = createLanguageButton(findPack(manager, "en"), "English", "en");

        buttonRow.getChildren().addAll(spanishButton, englishButton);

        continueButton.setDisable(true);
        continueButton.setDefaultButton(true);
        continueButton.getStyleClass().add("language-primary-button");
        continueButton.setMaxWidth(Double.MAX_VALUE);
        continueButton.setOnAction(e -> {
            Toggle selected = languageGroup.getSelectedToggle();
            if (selected != null) {
                selectedLanguage = String.valueOf(selected.getUserData());
                stage.close();
            }
        });

        languageGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            continueButton.setDisable(newToggle == null);
            updateContinueLabel(newToggle);
        });
        updateContinueLabel(languageGroup.getSelectedToggle());

        VBox buttonColumn = new VBox(18, buttonRow, continueButton);
        buttonColumn.setAlignment(Pos.CENTER);
        root.getChildren().addAll(title, subtitle, buttonColumn);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        if (themeId != null) {
            ThemeHelper.applyTheme(scene, themeId);
        }
    }

    public String showAndWait() {
        stage.showAndWait();
        return selectedLanguage;
    }

    private ToggleButton createLanguageButton(LanguagePack pack, String fallbackName, String fallbackId) {
        String label = pack != null ? pack.displayName() : fallbackName;
        String languageId = pack != null ? pack.id() : fallbackId;
        ToggleButton button = new ToggleButton(label);
        button.getStyleClass().add("language-choice-button");
        button.setToggleGroup(languageGroup);
        button.setUserData(languageId);
        button.setMinWidth(150);
        button.setPrefHeight(70);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    private LanguagePack findPack(LanguageManager manager, String id) {
        for (LanguagePack pack : manager.getAvailableLanguages()) {
            if (pack != null && pack.id().equals(id)) {
                return pack;
            }
        }
        return null;
    }

    private void updateContinueLabel(Toggle selection) {
        if (selection == null) {
            continueButton.setText("Siguiente / Next");
            return;
        }
        String langId = String.valueOf(selection.getUserData());
        continueButton.setText("en".equals(langId) ? "Next" : "Siguiente");
    }
}
