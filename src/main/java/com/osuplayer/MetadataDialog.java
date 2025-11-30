package com.osuplayer;

import java.util.List;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;

public final class MetadataDialog {

    private MetadataDialog() {
    }

    public static void show(String displayName, MusicManager.SongMetadataDetails details, Scene ownerScene) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle("Metadata de la canción");
        alert.setHeaderText(displayName);

        if (details == null) {
            alert.setContentText("No se encontró metadata para esta canción.");
            alert.showAndWait();
            return;
        }

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        grid.getStyleClass().add("metadata-grid");

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setPercentWidth(30);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setPercentWidth(70);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        row = addRow(grid, row, "Título:", safe(details.title));
        row = addRow(grid, row, "Artista:", safe(details.artist));
        row = addRow(grid, row, "Mapper:", safe(details.mapper));
        row = addRow(grid, row, "Dificultad:", safe(details.difficulty));
        row = addRow(grid, row, "Beatmap ID:", safe(details.beatmapId));
        row = addRow(grid, row, "Beatmap Set ID:", safe(details.beatmapSetId));
        row = addRow(grid, row, "Fuente:", safe(details.source));
        row = addRow(grid, row, "Tags:", formatTags(details.tags));
        row = addRow(grid, row, "Audio:", safe(details.audioPath));
        row = addRow(grid, row, "Vídeo:", safe(details.videoPath));
        row = addRow(grid, row, "Offset de vídeo:", formatOffset(details.videoOffsetMillis));
        row = addRow(grid, row, "Fondo:", safe(details.backgroundPath));
        addRow(grid, row, "Carpeta:", safe(details.baseFolder));

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setContent(grid);
        dialogPane.setPrefWidth(520);
        dialogPane.getStyleClass().add("metadata-dialog");
        if (ownerScene != null && ownerScene.getStylesheets() != null) {
            dialogPane.getStylesheets().addAll(ownerScene.getStylesheets());
        }

        alert.showAndWait();
    }

    private static int addRow(GridPane grid, int rowIndex, String labelText, String valueText) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metadata-label");
        Label value = new Label(valueText);
        value.getStyleClass().add("metadata-value");
        value.setWrapText(true);
        grid.add(label, 0, rowIndex);
        grid.add(value, 1, rowIndex);
        return rowIndex + 1;
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    private static String formatTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "-";
        return String.join(", ", tags);
    }

    private static String formatOffset(long offsetMillis) {
        if (offsetMillis == 0) return "0 ms";
        return offsetMillis + " ms";
    }
}
