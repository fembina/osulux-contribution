package com.osuplayer.ui;

import com.osuplayer.lang.I18n;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.scene.text.TextAlignment;

public class ProgressDialog {

    private static final double INFO_COLUMN_WIDTH = 320d;
    private static final double ICON_SIZE = 120d;
    private static final double DIALOG_WIDTH = INFO_COLUMN_WIDTH + ICON_SIZE + 52d;
    private static final double DIALOG_HEIGHT = ICON_SIZE + 140d;

    private final Stage stage;
    private final Node loaderView = LoadingAnimationFactory.createLoader(ICON_SIZE);
    private final Label titleLabel = new Label(I18n.tr("Preparando..."));
    private final Label detailLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0d);
    private final Label progressPercentLabel = new Label("0%");
    private final String progressPrefix;

    public ProgressDialog(Window owner, String title, String progressPrefix, String themeId) {
        stage = new Stage();
        stage.initStyle(StageStyle.UTILITY);
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle(title == null || title.isBlank() ? I18n.tr("Progreso") : title);

        this.progressPrefix = (progressPrefix == null || progressPrefix.isBlank())
                ? I18n.tr("Progreso")
                : progressPrefix;

        titleLabel.setAlignment(Pos.CENTER_LEFT);
        titleLabel.setWrapText(true);
        titleLabel.setMinWidth(INFO_COLUMN_WIDTH);
        titleLabel.setPrefWidth(INFO_COLUMN_WIDTH);
        titleLabel.setMaxWidth(INFO_COLUMN_WIDTH);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        detailLabel.setWrapText(true);
        detailLabel.setStyle("-fx-font-size: 12px;");
        detailLabel.setAlignment(Pos.TOP_LEFT);
        detailLabel.setTextAlignment(TextAlignment.LEFT);
        detailLabel.setMinWidth(INFO_COLUMN_WIDTH);
        detailLabel.setPrefWidth(INFO_COLUMN_WIDTH);
        detailLabel.setMaxWidth(INFO_COLUMN_WIDTH);
        detailLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        detailLabel.setMinHeight(38);
        detailLabel.setPrefHeight(38);
        detailLabel.setMaxHeight(Region.USE_PREF_SIZE);

        progressBar.setProgress(0d);
        progressBar.setPrefWidth(INFO_COLUMN_WIDTH - 80);
        progressBar.setMinWidth(INFO_COLUMN_WIDTH - 120);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setFocusTraversable(false);
        progressBar.setMouseTransparent(true);

        progressPercentLabel.setMinWidth(48);
        progressPercentLabel.setPrefWidth(48);
        progressPercentLabel.setMaxWidth(48);
        progressPercentLabel.setAlignment(Pos.CENTER_RIGHT);
        progressPercentLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        HBox progressRow = new HBox(8, progressBar, progressPercentLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.setMinWidth(INFO_COLUMN_WIDTH);
        progressRow.setPrefWidth(INFO_COLUMN_WIDTH);
        progressRow.setMaxWidth(INFO_COLUMN_WIDTH);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        VBox infoColumn = new VBox(10, titleLabel, progressRow, detailLabel);
        infoColumn.setAlignment(Pos.CENTER_LEFT);
        infoColumn.setFillWidth(true);
        infoColumn.setMinWidth(INFO_COLUMN_WIDTH);
        infoColumn.setPrefWidth(INFO_COLUMN_WIDTH);
        infoColumn.setMaxWidth(INFO_COLUMN_WIDTH);

        StackPane iconWrapper = new StackPane(loaderView);
        iconWrapper.setAlignment(Pos.CENTER);
        iconWrapper.setMinSize(ICON_SIZE, ICON_SIZE);
        iconWrapper.setPrefSize(ICON_SIZE, ICON_SIZE);
        iconWrapper.setMaxSize(ICON_SIZE + 6, ICON_SIZE + 6);
        iconWrapper.setStyle("-fx-background-color: transparent;");

        HBox root = new HBox(20, infoColumn, iconWrapper);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(15));
        root.setMinWidth(DIALOG_WIDTH - 16);
        root.setPrefWidth(DIALOG_WIDTH - 16);
        root.setMaxWidth(DIALOG_WIDTH - 16);
        HBox.setHgrow(infoColumn, Priority.ALWAYS);
        Scene scene = new Scene(root);
        ThemeHelper.applyTheme(scene, themeId);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setWidth(DIALOG_WIDTH);
        stage.setMinWidth(DIALOG_WIDTH);
        stage.setMaxWidth(DIALOG_WIDTH);
        stage.setHeight(DIALOG_HEIGHT);
        stage.setMinHeight(DIALOG_HEIGHT);
        stage.setMaxHeight(DIALOG_HEIGHT);
        stage.setOnCloseRequest(evt -> evt.consume());

        titleLabel.setText(this.progressPrefix + "...");
    }

    public void show() {
        Platform.runLater(() -> {
            if (!stage.isShowing()) {
                stage.show();
            }
        });
    }

    public void close() {
        Platform.runLater(() -> {
            if (stage.isShowing()) {
                stage.close();
            }
        });
    }

    public void updateProgress(double progress, String currentItem) {
        Platform.runLater(() -> {
            double clamped = Math.max(0d, Math.min(1d, progress));
            int percent = (int) Math.round(clamped * 100);
            progressBar.setProgress(clamped);
            progressPercentLabel.setText(percent + "%");
            detailLabel.setText(currentItem == null ? "" : currentItem);
        });
    }

    public void updateMessage(String message) {
        Platform.runLater(() -> detailLabel.setText(message == null ? "" : message));
    }

}
