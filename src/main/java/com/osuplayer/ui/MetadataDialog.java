package com.osuplayer.ui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

import com.osuplayer.lang.I18n;
import com.osuplayer.playback.MusicManager;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

public final class MetadataDialog {

    private MetadataDialog() {
    }

    public static void show(String displayName, MusicManager.SongMetadataDetails details, Scene ownerScene) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle(I18n.tr("Metadata de la canción"));
        alert.setHeaderText(displayName);

        Window owner = ownerScene == null ? null : ownerScene.getWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }

        if (details == null) {
            alert.setContentText(I18n.tr("No se encontró metadata para esta canción."));
            applyOwnerIcon(alert, owner);
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
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, valueColumn);

        int row = 0;
        row = addRow(grid, row, I18n.tr("Título:"), selectable(details.title));
        row = addRow(grid, row, I18n.tr("Artista:"), selectable(details.artist));
        row = addRow(grid, row, I18n.tr("Mapper:"), selectable(details.mapper));
        row = addRow(grid, row, I18n.tr("Dificultad:"), selectable(details.difficulty));
        row = addRow(grid, row, I18n.tr("Beatmap ID:"), selectable(details.beatmapId));
        row = addRow(grid, row, I18n.tr("Beatmap Set ID:"), selectable(details.beatmapSetId));
        row = addRow(grid, row, I18n.tr("OSU! page:"), linkOrValue(buildOsuUrl(details), OpenMode.OPEN_BROWSER));
        row = addRow(grid, row, I18n.tr("Fuente:"), selectable(details.source));
        row = addRow(grid, row, I18n.tr("Tags:"), selectable(formatTags(details.tags)));
        row = addRow(grid, row, I18n.tr("Audio:"), linkOrValue(details.audioPath, OpenMode.SELECT_FILE));
        if (hasVideo(details.videoPath)) {
            row = addRow(grid, row, I18n.tr("Vídeo:"), selectable(details.videoPath));
            row = addRow(grid, row, I18n.tr("Offset de vídeo:"), selectable(formatOffset(details.videoOffsetMillis)));
        }
        row = addRow(grid, row, I18n.tr("Fondo:"), linkOrValue(details.backgroundPath, OpenMode.SELECT_FILE));
        addRow(grid, row, I18n.tr("Carpeta:"), linkOrValue(details.baseFolder, OpenMode.OPEN_FOLDER));

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setContent(grid);
        dialogPane.setPrefWidth(520);
        dialogPane.getStyleClass().add("metadata-dialog");
        if (ownerScene != null && ownerScene.getStylesheets() != null) {
            dialogPane.getStylesheets().addAll(ownerScene.getStylesheets());
        }

        applyOwnerIcon(alert, owner);
        alert.showAndWait();
    }

    private static int addRow(GridPane grid, int rowIndex, String labelText, javafx.scene.Node valueNode) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metadata-label");
        grid.add(label, 0, rowIndex);
        grid.add(valueNode, 1, rowIndex);
        return rowIndex + 1;
    }

    private static TextArea selectable(String value) {
        String text = safe(value);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setFocusTraversable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(40);
        area.setPrefWidth(380);
        area.setMinWidth(140);
        area.setMaxWidth(Double.MAX_VALUE);
        area.setPrefRowCount(computeRows(text));
        area.setMinHeight(Region.USE_PREF_SIZE);
        area.setMaxHeight(Region.USE_PREF_SIZE);
        area.setBorder(null);
        area.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-color: transparent; -fx-padding: 0; -fx-text-fill: -fx-text-base-color; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-highlight-fill: #3a78ff; -fx-highlight-text-fill: white;");
        area.setOnMouseClicked(e -> area.requestFocus());
        return area;
    }

    private static javafx.scene.Node linkOrValue(String path, OpenMode mode) {
        String text = safe(path);
        if (text.equals("-")) {
            return selectable(text);
        }
        Hyperlink link = new Hyperlink(text);
        link.setWrapText(true);
        link.setOnAction(e -> {
            if (mode == OpenMode.OPEN_BROWSER) {
                openUrl(text);
            } else {
                openPath(new File(text), mode);
            }
        });
        return link;
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

    private static int computeRows(String text) {
        if (text == null || text.isBlank()) return 1;
        int width = 55;
        int rows = (text.length() / width) + 1;
        return Math.min(12, Math.max(1, rows));
    }

    private static boolean hasVideo(String videoPath) {
        return videoPath != null && !videoPath.isBlank();
    }

    private static void openPath(File target, OpenMode mode) {
        if (target == null) return;
        try {
            if (mode == OpenMode.OPEN_FOLDER) {
                openFolder(target);
                return;
            }
            if (mode == OpenMode.SELECT_FILE) {
                if (!target.exists()) {
                    File parent = target.getParentFile();
                    if (parent == null || !parent.exists()) return;
                }
                if (isWindows()) {
                    new ProcessBuilder("explorer.exe", "/select,", target.getAbsolutePath()).start();
                } else {
                    openFolder(target);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            if (!Desktop.isDesktopSupported()) return;
            Desktop.getDesktop().browse(new URI(url));
        } catch (IOException | URISyntaxException ignored) {
        }
    }

    private static void openFolder(File target) throws IOException {
        File folder = target.isDirectory() ? target : target.getParentFile();
        if (folder == null || !folder.exists()) return;
        if (!Desktop.isDesktopSupported()) return;
        Desktop.getDesktop().open(folder);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static String buildOsuUrl(MusicManager.SongMetadataDetails details) {
        if (details == null) return "-";
        String setId = trim(details.beatmapSetId);
        if (setId == null || setId.isBlank() || "0".equals(setId) || "-1".equals(setId)) return "-";
        return "https://osu.ppy.sh/beatmapsets/" + setId;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static void applyOwnerIcon(Alert alert, Window owner) {
        alert.setOnShown((DialogEvent event) -> {
            if (owner instanceof Stage ownerStage) {
                Stage dialogStage = (Stage) alert.getDialogPane().getScene().getWindow();
                if (dialogStage != null) {
                    dialogStage.getIcons().setAll(ownerStage.getIcons());
                }
            }
        });
    }

    private enum OpenMode {
        OPEN_FOLDER,
        SELECT_FILE,
        OPEN_BROWSER
    }
}
