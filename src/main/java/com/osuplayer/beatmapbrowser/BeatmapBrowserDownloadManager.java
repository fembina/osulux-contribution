package com.osuplayer.beatmapbrowser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.osuplayer.beatmaps.BeatmapParser;
import com.osuplayer.config.ConfigManager;
import com.osuplayer.beatmaps.download.MirrorBeatmapDownloadService;
import com.osuplayer.beatmaps.download.OsuBeatmapDownloadService;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.lang.I18n;
import com.osuplayer.osu.OsuApiClient;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.util.Duration;

public class BeatmapBrowserDownloadManager {

    private final ConfigManager configManager;
    private final OsuApiClient apiClient;
    private final OsuBeatmapDownloadService downloadService;
    private final MirrorBeatmapDownloadService mirrorDownloadService;
    private final ExecutorService executor;
    private final BeatmapBrowserView view;
    private final Consumer<Path> onLibraryUpdated;
    private final DialogCallbacks dialogs;
    private final BeatmapParser beatmapParser = new BeatmapParser();

    private final Map<Long, DoubleProperty> downloadProgress = new ConcurrentHashMap<>();
    private final Map<Long, Timeline> progressHideTimers = new HashMap<>();

    public BeatmapBrowserDownloadManager(ConfigManager configManager,
                                         OsuApiClient apiClient,
                                         OsuBeatmapDownloadService downloadService,
                                         MirrorBeatmapDownloadService mirrorDownloadService,
                                         ExecutorService executor,
                                         BeatmapBrowserView view,
                                         Consumer<Path> onLibraryUpdated,
                                         DialogCallbacks dialogs) {
        this.configManager = configManager;
        this.apiClient = apiClient;
        this.downloadService = downloadService;
        this.mirrorDownloadService = mirrorDownloadService;
        this.executor = executor;
        this.view = view;
        this.onLibraryUpdated = onLibraryUpdated;
        this.dialogs = dialogs;
    }

    public void downloadSelected() {
        OsuApiClient.BeatmapsetSummary selected = view.resultsView().getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String lastFolder = configManager.getLastFolder();
        if (lastFolder == null || lastFolder.isBlank()) {
            dialogs.showError("Carpeta no configurada", "Primero abre tu carpeta de canciones desde Osulux.");
            return;
        }

        Path songsDir;
        try {
            songsDir = Paths.get(lastFolder);
        } catch (InvalidPathException ex) {
            dialogs.showError("Ruta inválida", "La carpeta guardada no es una ruta válida. Vuelve a seleccionarla en Osulux.");
            return;
        }
        disableDownload(true);

        SourceOption option = view.sourceCombo().getValue();
        boolean includeVideo = view.includeVideoCheck().isSelected();
        OsuApiClient.BeatmapsetSummary preparedSummary = ensureVideoMetadata(selected, includeVideo);
        boolean expectVideo = includeVideo && preparedSummary.video();
        boolean preferOfficialDownload = option != null && option.official() && apiClient.hasUserSession();
        long beatmapId = preparedSummary.id();
        prepareProgressForDownload(beatmapId);
        DownloadProgressListener progressListener = (id, downloaded, total) -> handleDownloadProgress(id, downloaded, total);

        var task = new javafx.concurrent.Task<DownloadOutcome>() {
            @Override
            protected DownloadOutcome call() throws Exception {
                OsuApiClient.BeatmapsetSummary summary = preparedSummary;
                if (preferOfficialDownload) {
                    try {
                        OsuBeatmapDownloadService.DownloadResult officialResult = downloadService.downloadAndExtract(
                                summary,
                                songsDir,
                                includeVideo,
                                progressListener);
                        Path folder = stripVideoAssetsIfNeeded(officialResult.extractedFolder(), includeVideo);
                        return new DownloadOutcome(folder, "API oficial", null);
                    } catch (IOException ex) {
                        String fallbackNote = "Falló la API oficial: " + ex.getMessage();
                        return downloadFromMirrorsWithFallback(summary, songsDir, fallbackNote, progressListener, includeVideo, expectVideo);
                    }
                }
                return downloadFromMirrorsWithFallback(summary, songsDir, null, progressListener, includeVideo, expectVideo);
            }
        };

        task.setOnSucceeded(evt -> {
            DownloadOutcome outcome = task.getValue();
            Path folder = outcome.folder();
            view.showStatusMessage(() -> {
                String folderName = folder == null ? "" : folder.getFileName().toString();
                String sourceLabel = outcome.sourceLabel() == null ? "" : I18n.tr(outcome.sourceLabel());
                String message = I18n.trf("Importado desde %s: %s", sourceLabel, folderName);
                if (outcome.fallbackNote() != null && !outcome.fallbackNote().isBlank()) {
                    message += " (" + outcome.fallbackNote() + ")";
                }
                return message;
            });
            markDownloadCompleted(beatmapId);
            disableDownload(false);
            if (configManager.isOsuAutoRefreshAfterImport() && onLibraryUpdated != null) {
                Path completedFolder = outcome.folder();
                if (completedFolder != null) {
                    Path finalFolder = completedFolder;
                    Platform.runLater(() -> onLibraryUpdated.accept(finalFolder));
                }
            }
        });

        task.setOnFailed(evt -> {
            Throwable ex = task.getException();
            logDownloadFailure(beatmapId, ex);
            dialogs.showError("No se pudo descargar el beatmap", ex == null ? "Error desconocido" : ex.getMessage());
            markDownloadFailed(beatmapId);
            disableDownload(false);
        });

        executor.submit(task);
    }

    public DoubleProperty getDownloadProgressProperty(long beatmapsetId) {
        return downloadProgress.computeIfAbsent(beatmapsetId, id -> new SimpleDoubleProperty(0d));
    }

    private void disableDownload(boolean running) {
        view.downloadButton().setDisable(running || view.resultsView().getSelectionModel().getSelectedItem() == null);
        view.progressIndicator().setVisible(running);
    }

    private void prepareProgressForDownload(long beatmapsetId) {
        DoubleProperty property = getDownloadProgressProperty(beatmapsetId);
        Platform.runLater(() -> {
            cancelProgressHide(beatmapsetId);
            property.set(0d);
        });
    }

    private void handleDownloadProgress(long beatmapsetId, long downloadedBytes, long totalBytes) {
        DoubleProperty property = getDownloadProgressProperty(beatmapsetId);
        double value = (totalBytes > 0 && downloadedBytes >= 0)
                ? Math.min(1d, (double) downloadedBytes / (double) totalBytes)
                : -1d;
        Platform.runLater(() -> {
            property.set(value);
            if (value < 1d) {
                cancelProgressHide(beatmapsetId);
            }
        });
    }

    private void markDownloadCompleted(long beatmapsetId) {
        DoubleProperty property = getDownloadProgressProperty(beatmapsetId);
        Platform.runLater(() -> {
            property.set(1d);
            scheduleProgressHide(beatmapsetId);
        });
    }

    private void markDownloadFailed(long beatmapsetId) {
        DoubleProperty property = getDownloadProgressProperty(beatmapsetId);
        Platform.runLater(() -> {
            property.set(0d);
            cancelProgressHide(beatmapsetId);
        });
    }

    private void scheduleProgressHide(long beatmapsetId) {
        Timeline existing = progressHideTimers.remove(beatmapsetId);
        if (existing != null) {
            existing.stop();
        }
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1.5), e -> {
            DoubleProperty property = downloadProgress.get(beatmapsetId);
            if (property != null) {
                property.set(0d);
            }
            progressHideTimers.remove(beatmapsetId);
        }));
        progressHideTimers.put(beatmapsetId, timeline);
        timeline.play();
    }

    private void cancelProgressHide(long beatmapsetId) {
        Timeline existing = progressHideTimers.remove(beatmapsetId);
        if (existing != null) {
            existing.stop();
        }
    }

    private DownloadOutcome downloadFromMirrorsWithFallback(OsuApiClient.BeatmapsetSummary summary,
                                                            Path songsDir,
                                                            String fallbackNote,
                                                            DownloadProgressListener progressListener,
                                                            boolean includeVideo,
                                                            boolean expectVideo) throws IOException {
        MirrorBeatmapDownloadService.MirrorDownloadResult mirrorResult = mirrorDownloadService.downloadAndExtract(
                summary,
                songsDir,
                null,
                progressListener);
        Path folder = stripVideoAssetsIfNeeded(mirrorResult.extractedFolder(), includeVideo);
        String source = mirrorResult.sourceName();
        String note = fallbackNote;

        if (expectVideo && !folderContainsVideo(folder)) {
            if (apiClient.hasUserSession()) {
                try {
                    deleteDirectoryQuietly(folder);
                    OsuBeatmapDownloadService.DownloadResult officialResult = downloadService.downloadAndExtract(
                            summary,
                            songsDir,
                            true,
                            progressListener);
                    folder = stripVideoAssetsIfNeeded(officialResult.extractedFolder(), includeVideo);
                    source = "API oficial";
                    note = appendNote(note, "El mirror no incluía video; se usó la API oficial.");
                } catch (IOException ex) {
                    note = appendNote(note, "No se pudo obtener el video desde la API oficial: " + ex.getMessage());
                }
            } else {
                note = appendNote(note, "El mirror no incluye videos. Inicia sesión con osu! y usa la API oficial para obtenerlos.");
            }
        }

        return new DownloadOutcome(folder, source, note);
    }

    private OsuApiClient.BeatmapsetSummary ensureVideoMetadata(OsuApiClient.BeatmapsetSummary original, boolean includeVideo) {
        if (!includeVideo || original == null || original.video()) {
            return original;
        }
        try {
            OsuApiClient.BeatmapsetSummary refreshed = apiClient.fetchBeatmapset(original.id());
            if (refreshed != null) {
                return refreshed;
            }
        } catch (IOException ignored) {}
        return original;
    }

    private boolean folderContainsVideo(Path folder) {
        if (folder == null) {
            return false;
        }
        File dir = folder.toFile();
        return dir.isDirectory() && beatmapParser.findVideoPath(dir) != null;
    }

    private Path stripVideoAssetsIfNeeded(Path folder, boolean includeVideo) {
        if (includeVideo || folder == null) {
            return folder;
        }
        removeVideoFiles(folder.toFile());
        removeVideoReferences(folder.toFile());
        return folder;
    }

    private void removeVideoFiles(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        boolean removed;
        do {
            removed = deleteSingleVideo(folder);
        } while (removed);
    }

    private boolean deleteSingleVideo(File folder) {
        String videoPath = beatmapParser.findVideoPath(folder);
        if (videoPath == null) {
            return false;
        }
        try {
            Files.deleteIfExists(Paths.get(videoPath));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void removeVideoReferences(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        List<File> osuFiles = beatmapParser.listOsuFiles(folder);
        if (osuFiles.isEmpty()) {
            return;
        }
        for (File osuFile : new ArrayList<>(osuFiles)) {
            try {
                List<String> lines = Files.readAllLines(osuFile.toPath(), StandardCharsets.UTF_8);
                List<String> updated = new ArrayList<>(lines.size());
                boolean inEvents = false;
                boolean changed = false;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if ("[Events]".equals(trimmed)) {
                        inEvents = true;
                        updated.add(line);
                        continue;
                    }
                    if (inEvents && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inEvents = false;
                    }
                    if (inEvents && trimmed.regionMatches(true, 0, "Video", 0, 5)) {
                        changed = true;
                        continue;
                    }
                    updated.add(line);
                }
                if (changed) {
                    Files.write(osuFile.toPath(), updated, StandardCharsets.UTF_8);
                }
            } catch (java.nio.charset.CharacterCodingException ignored) {
            } catch (IOException ignored) {}
        }
    }

    private void deleteDirectoryQuietly(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try {
            Files.walk(folder)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private void logDownloadFailure(long beatmapsetId, Throwable ex) {
        if (ex == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Osulux] Falló la descarga del beatmapset ")
                .append(beatmapsetId)
                .append(':').append(System.lineSeparator())
                .append(ex).append(System.lineSeparator());
        for (StackTraceElement element : ex.getStackTrace()) {
            sb.append("    at ").append(element).append(System.lineSeparator());
        }
        try {
            Files.writeString(Path.of("osulux-error.log"), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + " | " + addition;
    }

    private record DownloadOutcome(Path folder, String sourceLabel, String fallbackNote) { }
}
