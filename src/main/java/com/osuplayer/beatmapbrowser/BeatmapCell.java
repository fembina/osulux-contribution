package com.osuplayer.beatmapbrowser;

import java.util.Locale;
import java.util.function.LongFunction;

import com.osuplayer.lang.I18n;
import com.osuplayer.osu.OsuApiClient;

import javafx.beans.property.DoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

class BeatmapCell extends ListCell<OsuApiClient.BeatmapsetSummary> {

    private final LongFunction<DoubleProperty> progressProvider;
    private final Label textLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final VBox container = new VBox(6, textLabel, progressBar);
    private DoubleProperty boundProgress;
    private ChangeListener<Number> progressListener;

    BeatmapCell(LongFunction<DoubleProperty> progressProvider) {
        this.progressProvider = progressProvider;
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setStyle("-fx-accent: #2ecc71;");
        container.setFillWidth(true);
    }

    @Override
    protected void updateItem(OsuApiClient.BeatmapsetSummary item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            unbindProgress();
            setGraphic(null);
            setText(null);
            setContextMenu(null);
            return;
        }
        String updated = item.lastUpdated() == null ? "" : item.lastUpdated().toString();
        String videoLabel = I18n.tr(item.video() ? "Con video" : "Sin video");
        String format = I18n.tr("%s (%s)\nMapper: %s | BPM: %.0f | Estado: %s | ❤ %d | ▶ %d | %s | %s");
        textLabel.setText(String.format(Locale.ROOT,
            format,
            item.displayName(),
            item.id(),
            item.creator(),
            item.bpm(),
            item.status(),
            item.favouriteCount(),
            item.playCount(),
            updated,
            videoLabel));
        setGraphic(container);
        setText(null);
        bindProgress(item.id());
    }

    private void bindProgress(long beatmapId) {
        unbindProgress();
        if (progressProvider == null) {
            return;
        }
        boundProgress = progressProvider.apply(beatmapId);
        if (boundProgress == null) {
            return;
        }
        updateProgressBar(boundProgress.get());
        progressListener = (obs, oldVal, newVal) -> updateProgressBar(newVal == null ? 0d : newVal.doubleValue());
        boundProgress.addListener(progressListener);
    }

    private void unbindProgress() {
        if (boundProgress != null && progressListener != null) {
            boundProgress.removeListener(progressListener);
        }
        if (boundProgress != null) {
            boundProgress = null;
        }
        if (progressListener != null) {
            progressListener = null;
        }
        progressBar.setVisible(false);
        progressBar.setProgress(0d);
    }

    private void updateProgressBar(double value) {
        if (value == 0d) {
            progressBar.setVisible(false);
            progressBar.setProgress(0d);
            return;
        }

        if (value >= 1d) {
            progressBar.setVisible(false);
            progressBar.setProgress(1d);
            return;
        }

        if (value < 0d) {
            progressBar.setVisible(true);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            return;
        }

        progressBar.setVisible(true);
        progressBar.setProgress(value);
    }
}
