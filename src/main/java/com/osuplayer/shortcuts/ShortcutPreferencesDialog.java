package com.osuplayer.shortcuts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.osuplayer.lang.I18n;
import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.ui.ThemeHelper;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public class ShortcutPreferencesDialog {

    private final ShortcutManager shortcutManager;
    private final Map<ShortcutAction, Label> customValueLabels = new EnumMap<>(ShortcutAction.class);
    private final Map<ShortcutAction, Button> resetButtons = new EnumMap<>(ShortcutAction.class);
    private final Map<ShortcutAction, Label> builtInLabels = new EnumMap<>(ShortcutAction.class);

    private Stage dialogStage;
    private String currentThemeId;
    private final List<Image> iconImages = new ArrayList<>();

    public ShortcutPreferencesDialog(ShortcutManager shortcutManager) {
        this.shortcutManager = shortcutManager;
    }

    public void setIconImages(List<Image> icons) {
        iconImages.clear();
        if (icons != null && !icons.isEmpty()) {
            iconImages.addAll(icons);
        }
        applyIconsToStage(dialogStage);
    }

    public void applyTheme(String themeId) {
        this.currentThemeId = themeId;
        if (dialogStage != null) {
            applyThemeToScene(dialogStage.getScene());
        }
    }

    public void refreshLanguage() {
        if (dialogStage != null && dialogStage.isShowing()) {
            rebuildGrid();
        }
    }

    public void show(Window owner) {
        if (dialogStage == null) {
            dialogStage = createDialog(owner);
        } else if (dialogStage.getOwner() == null && owner != null) {
            dialogStage.initOwner(owner);
        }
        rebuildGrid();
        dialogStage.show();
        dialogStage.toFront();
    }

    private Stage createDialog(Window owner) {
        Stage stage = new Stage(StageStyle.UTILITY);
        LanguageBindings.bindStageTitle(stage, "Atajos de teclado");
        
        stage.initModality(Modality.NONE);
        if (owner != null) {
            stage.initOwner(owner);
        }

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        Label header = new Label();
        LanguageBindings.bindLabeled(header, "Consulta o personaliza los atajos disponibles. Los atajos existentes se mantienen activos aunque añadas uno nuevo.");
        header.setWrapText(true);
        header.setPadding(new Insets(0, 0, 10, 0));
        root.setTop(header);

        ScrollPane scrollPane = new ScrollPane(buildShortcutGrid());
        scrollPane.setFitToWidth(true);
        root.setCenter(scrollPane);

        Button closeButton = new Button();
        LanguageBindings.bindLabeled(closeButton, "Cerrar");
        closeButton.setOnAction(e -> dialogStage.close());
        HBox footer = new HBox(closeButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 0, 0, 0));
        root.setBottom(footer);

        Scene scene = new Scene(root, 760, 500);
        stage.setScene(scene);
        applyThemeToScene(scene);
        applyIconsToStage(stage);
        stage.setResizable(false); 
        stage.setAlwaysOnTop(true);
        return stage;
    }

    private void rebuildGrid() {
        if (dialogStage == null) {
            return;
        }
        ScrollPane scrollPane = new ScrollPane(buildShortcutGrid());
        scrollPane.setFitToWidth(true);
        BorderPane root = (BorderPane) dialogStage.getScene().getRoot();
        root.setCenter(scrollPane);
        refreshAllRows();
    }

    private GridPane buildShortcutGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(5));

        grid.add(boundHeader("Acción"), 0, 0);
        grid.add(boundHeader("Atajos existentes"), 1, 0);
        grid.add(boundHeader("Atajo personalizable"), 2, 0);
        grid.add(boundHeader("Acciones"), 3, 0);

        int row = 1;
        for (ShortcutAction action : ShortcutAction.values()) {
            addRow(grid, action, row);
            row += 2;
        }
        return grid;
    }

    private void addRow(GridPane grid, ShortcutAction action, int row) {
        Label actionLabel = new Label(action.getDisplayName());
        actionLabel.setStyle("-fx-font-weight: bold;");
        grid.add(actionLabel, 0, row);

        Label builtInLabel = new Label(formatBuiltInShortcuts(action.getFixedShortcutDescriptions()));
        builtInLabel.setWrapText(true);
        builtInLabel.setPrefWidth(200);
        builtInLabels.put(action, builtInLabel);
        grid.add(builtInLabel, 1, row);

        Label customLabel = new Label(shortcutManager.getDisplayText(action));
        customLabel.setPrefWidth(180);
        customLabel.setWrapText(true);
        customValueLabels.put(action, customLabel);
        grid.add(customLabel, 2, row);

        Button changeButton = new Button();
        LanguageBindings.bindLabeled(changeButton, "Cambiar");
        changeButton.setDisable(!action.isCustomizable());
        changeButton.setOnAction(e -> {
            if (action.isCustomizable()) {
                promptForShortcut(action);
            }
        });

        Button resetButton = new Button();
        LanguageBindings.bindLabeled(resetButton, "Restablecer");
        resetButton.setDisable(!action.isCustomizable() || !shortcutManager.hasCustomCombination(action));
        resetButton.setOnAction(e -> {
            if (!action.isCustomizable()) {
                return;
            }
            shortcutManager.clearCustomCombination(action);
            refreshRow(action);
        });
        resetButtons.put(action, resetButton);

        HBox buttons = new HBox(5, changeButton, resetButton);
        grid.add(buttons, 3, row);
        GridPane.setHgrow(buttons, Priority.NEVER);

        Label description = new Label(action.getDescription());
        description.setWrapText(true);
        description.setStyle("-fx-text-fill: -fx-text-base-color;");
        grid.add(description, 0, row + 1, 4, 1);
    }

    private void promptForShortcut(ShortcutAction action) {
        Stage captureStage = new Stage();
        captureStage.setTitle(I18n.trf("Nuevo atajo - %s", action.getDisplayName()));
        captureStage.initModality(Modality.WINDOW_MODAL);
        if (dialogStage != null) {
            captureStage.initOwner(dialogStage);
        }
        applyIconsToStage(captureStage);

        Label instructions = new Label(I18n.tr("Pulsa la nueva combinación de teclas (Esc para cancelar)."));
        instructions.setWrapText(true);
        StackPane container = new StackPane(instructions);
        container.setPadding(new Insets(20));

        Scene scene = new Scene(container, 420, 140);
        applyThemeToScene(scene);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                captureStage.close();
                event.consume();
                return;
            }
            KeyCodeCombination combination = shortcutManager.buildCombinationFromEvent(event);
            if (combination == null) {
                instructions.setText(I18n.tr("Por favor, utiliza una tecla distinta de los modificadores."));
                event.consume();
                return;
            }
            if (shortcutManager.isCombinationInUse(combination, action)) {
                ShortcutAction conflict = shortcutManager
                    .findActionForCombination(combination, action)
                    .orElse(null);
                String conflictText = conflict != null ? conflict.getDisplayName() : I18n.tr("otro atajo");
                instructions.setText(I18n.trf("Esa combinación ya está asignada a %s. Usa otra o Esc.", conflictText));
                event.consume();
                return;
            }
            shortcutManager.setCustomCombination(action, combination);
            refreshRow(action);
            captureStage.close();
            event.consume();
        });

        captureStage.setScene(scene);
        captureStage.showAndWait();
    }

    private void applyIconsToStage(Stage stage) {
        if (stage == null || iconImages.isEmpty()) {
            return;
        }
        stage.getIcons().setAll(iconImages);
    }

    private void applyThemeToScene(Scene scene) {
        ThemeHelper.applyTheme(scene, currentThemeId);
    }

    private void refreshAllRows() {
        for (ShortcutAction action : ShortcutAction.values()) {
            refreshRow(action);
        }
    }

    private void refreshRow(ShortcutAction action) {
        Label label = customValueLabels.get(action);
        if (label != null) {
            label.setText(shortcutManager.getDisplayText(action));
        }
        Label builtIn = builtInLabels.get(action);
        if (builtIn != null) {
            builtIn.setText(formatBuiltInShortcuts(action.getFixedShortcutDescriptions()));
        }
        Button resetButton = resetButtons.get(action);
        if (resetButton != null) {
            boolean disable = !action.isCustomizable() || !shortcutManager.hasCustomCombination(action);
            resetButton.setDisable(disable);
        }
    }

    private String formatBuiltInShortcuts(List<String> builtIns) {
        if (builtIns == null || builtIns.isEmpty()) {
            return "N/A";
        }
        return String.join("\n", builtIns);
    }

    private Label boundHeader(String spanish) {
        Label label = new Label();
        LanguageBindings.bindLabeled(label, spanish);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }
}