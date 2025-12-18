package com.osuplayer.beatmapbrowser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.osuplayer.config.ConfigManager;
import com.osuplayer.beatmaps.download.MirrorBeatmapDownloadService;
import com.osuplayer.beatmaps.download.OsuBeatmapDownloadService;
import com.osuplayer.lang.I18n;
import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.lang.LanguageManager;
import com.osuplayer.mirrors.MirrorServer;
import com.osuplayer.mirrors.MirrorServers;
import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.osu.OsuAuthorizationHelper;
import com.osuplayer.ui.ThemeHelper;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

public class OsuBeatmapBrowserDialog {

    private static final String OSU_OAUTH_URL = "https://osu.ppy.sh/home/account/edit#oauth";
    private static final String DEFAULT_PREVIEW_MESSAGE_KEY = "Selecciona una canción para ver la carátula y reproducir una preview.";
    private static final String ICON_PLAY = "\u25B6";
    private static final String ICON_PAUSE = "\u23F8";
    private static final String ICON_LOADING = "\u22EF";
    private static final String ICON_RETRY = "\u21BB";
    private static final double BASE_DIALOG_WIDTH = 1420d;
    private static final double BASE_DIALOG_HEIGHT = 720d;
    private static final double OFFICIAL_HEIGHT_EXTRA = 200d;

    private final ConfigManager configManager;
    private final OsuApiClient apiClient;
    private final OsuBeatmapDownloadService downloadService;
    private final List<MirrorServer> mirrorServers = MirrorServers.all();
    private final MirrorBeatmapDownloadService mirrorDownloadService = new MirrorBeatmapDownloadService(mirrorServers);
    private final Consumer<Path> onLibraryUpdated;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osu-search-worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osu-download-worker");
        t.setDaemon(true);
        return t;
    });

    private Stage stage;
    private String currentTheme;
    private final List<Image> iconImages = new ArrayList<>();
    private String loggedInUsername;

    private final OsuAuthorizationHelper authorizationHelper = new OsuAuthorizationHelper();
    private final ObservableList<OsuApiClient.BeatmapsetSummary> currentResults = FXCollections.observableArrayList();
    private final NumberFormat integerFormat = NumberFormat.getIntegerInstance();
    private final LanguageManager languageManager = LanguageManager.getInstance();
    private final ChangeListener<String> languageChangeListener = (obs, oldId, newId) ->
        Platform.runLater(this::refreshLanguageDependentTexts);

    private BeatmapBrowserView view;
    private BeatmapBrowserSearchManager searchManager;
    private BeatmapBrowserDownloadManager downloadManager;
    private CredentialHelperManager credentialHelperManager;
    private BeatmapPreviewPlayer previewPlayer;
    private OsuApiClient.BeatmapsetSummary selectedPreview;
    private String lastPreviewStatsText = "";
    private double lastNonOfficialHeight = BASE_DIALOG_HEIGHT;
    private Boolean pendingOfficialVisibility;

    public OsuBeatmapBrowserDialog(ConfigManager configManager, Consumer<Path> onLibraryUpdated) {
        this.configManager = configManager;
        this.apiClient = new OsuApiClient(configManager);
        this.downloadService = new OsuBeatmapDownloadService(apiClient);
        this.onLibraryUpdated = onLibraryUpdated;
        this.currentTheme = configManager.getTheme();
        this.integerFormat.setGroupingUsed(true);
    }

    public void setIconImages(List<Image> icons) {
        iconImages.clear();
        if (icons != null && !icons.isEmpty()) {
            iconImages.addAll(icons);
        }
        if (stage != null) {
            applyIcons(stage);
        }
    }

    public void applyTheme(String themeId) {
        this.currentTheme = (themeId == null || themeId.isBlank()) ? null : themeId;
        if (stage != null) {
            applyThemeToScene(stage.getScene());
        }
        if (credentialHelperManager != null) {
            credentialHelperManager.hide();
        }
    }

    public void refreshLanguage() {
        Platform.runLater(() -> {
            refreshLanguageDependentTexts();
            if (view != null) {
                view.refreshLanguageNow();
            }
        });
    }

    public void show(Stage owner) {
        if (stage == null) {
            stage = buildStage();
        }
        applyPendingOfficialVisibility();
        updateLoginControls();
        fetchCurrentUserNameIfNeeded();
        applyThemeToScene(stage.getScene());
        stage.show();
        stage.toFront();
    }

    public void shutdown() {
        languageManager.languageIdProperty().removeListener(languageChangeListener);
        searchExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        Platform.runLater(() -> {
            if (previewPlayer != null) {
                previewPlayer.dispose();
                previewPlayer = null;
            }
            hideDialog();
            if (stage != null) {
                Stage current = stage;
                stage = null;
                current.close();
            }
            if (credentialHelperManager != null) {
                credentialHelperManager.shutdown();
            }
        });
    }

    private Stage buildStage() {
        view = new BeatmapBrowserView(configManager, currentResults, mirrorServers);
        languageManager.languageIdProperty().addListener(languageChangeListener);
        view.setOfficialControlsVisibilityListener(this::handleOfficialControlsVisibilityChanged);
        searchManager = new BeatmapBrowserSearchManager(apiClient, mirrorServers, searchExecutor, currentResults, view, dialogCallbacks);
        downloadManager = new BeatmapBrowserDownloadManager(configManager, apiClient, downloadService, mirrorDownloadService, downloadExecutor, view, onLibraryUpdated, dialogCallbacks);
        credentialHelperManager = new CredentialHelperManager(
            view,
            authorizationHelper,
            () -> stage,
            this::applyThemeToScene,
            this::applyIcons,
            this::openOsuProfileInBrowser);

        view.configureResultsList(downloadManager::getDownloadProgressProperty);
        view.saveCredentialsButton().setOnAction(e -> saveCredentials());
        view.searchButton().setOnAction(e -> searchManager.triggerSearch(true));
        view.queryField().setOnAction(e -> searchManager.triggerSearch(true));
        view.previousButton().setOnAction(e -> searchManager.changePage(-1));
        view.nextButton().setOnAction(e -> searchManager.changePage(1));
        view.downloadButton().setOnAction(e -> downloadManager.downloadSelected());
        view.sourceCombo().valueProperty().addListener((obs, oldVal, newVal) -> searchManager.refreshSourceControls());
        view.credentialHelperButton().setOnAction(e -> credentialHelperManager.start());
        view.loginButton().setOnAction(e -> startLoginFlow());
        view.logoutButton().setOnAction(e -> logout());
        configurePreviewControls();

        view.includeVideoCheck().setSelected(configManager.isOsuDownloadWithVideo());
        view.includeVideoCheck().selectedProperty().addListener((obs, oldVal, newVal) -> configManager.setOsuDownloadWithVideo(newVal));
        view.autoRefreshCheck().setSelected(configManager.isOsuAutoRefreshAfterImport());
        view.autoRefreshCheck().selectedProperty().addListener((obs, oldVal, newVal) -> configManager.setOsuAutoRefreshAfterImport(newVal));

        BorderPane root = view.root();
        Stage dialog = new Stage();
        dialog.initModality(Modality.NONE);
        LanguageBindings.bindStageTitle(dialog, "Buscar y descargar beatmaps de OSU!");
        applyIcons(dialog);

        dialog.setMinWidth(BASE_DIALOG_WIDTH);
        dialog.setMinHeight(BASE_DIALOG_HEIGHT);

        Scene scene = new Scene(root, BASE_DIALOG_WIDTH, BASE_DIALOG_HEIGHT);
        applyThemeToScene(scene);
        dialog.setScene(scene);
        dialog.setOnCloseRequest(evt -> {
            evt.consume();
            hideDialog();
        });
        return dialog;
    }

    private void hideDialog() {
        if (previewPlayer != null) {
            previewPlayer.stop();
        }
        if (credentialHelperManager != null) {
            credentialHelperManager.hide();
        }
        if (stage != null) {
            stage.hide();
        }
    }

    private void saveCredentials() {
        configManager.setOsuClientId(safeText(view.clientIdField()));
        configManager.setOsuClientSecret(safeText(view.clientSecretField()));
        apiClient.invalidateClientToken();
        apiClient.clearUserSession();
        loggedInUsername = null;
        updateLoginControls();
        showInfo(
            I18n.tr("Credenciales guardadas"),
            I18n.tr("Se actualizaron los datos para la API de osu!. Inicia sesión nuevamente si quieres descargar beatmaps.")
        );
    }

    private void startLoginFlow() {
        String clientIdText = safeText(view.clientIdField());
        String clientSecretText = safeText(view.clientSecretField());
        if (clientIdText.isBlank() || clientSecretText.isBlank()) {
            showError(
                I18n.tr("Faltan credenciales"),
                I18n.tr("Guarda tu client_id y client_secret antes de iniciar sesión.")
            );
            return;
        }

        int clientIdValue;
        try {
            clientIdValue = Integer.parseInt(clientIdText);
        } catch (NumberFormatException ex) {
            showError(I18n.tr("Client ID inválido"), I18n.tr("El client_id debe ser numérico."));
            return;
        }

        boolean credentialsChanged = !clientIdText.equals(configManager.getOsuClientId())
                || !clientSecretText.equals(configManager.getOsuClientSecret());
        if (credentialsChanged) {
            apiClient.invalidateClientToken();
        }
        configManager.setOsuClientId(clientIdText);
        configManager.setOsuClientSecret(clientSecretText);

        disableLoginControls(true);

        javafx.concurrent.Task<String> loginTask = new javafx.concurrent.Task<>() {
            @Override
            protected String call() throws Exception {
                OsuApiClient.OAuthToken token = authorizationHelper.startAuthorization(clientIdValue, clientSecretText);
                apiClient.applyUserToken(token);
                try {
                    OsuApiClient.OsuUser user = apiClient.fetchCurrentUser();
                    return user.username();
                } catch (IOException ignored) {
                    return null;
                }
            }
        };

        loginTask.setOnSucceeded(evt -> {
            loggedInUsername = loginTask.getValue();
            updateLoginControls();
            disableLoginControls(false);
            if (loggedInUsername != null && !loggedInUsername.isBlank()) {
                showInfo(
                    I18n.tr("Sesión iniciada"),
                    I18n.trf("Conectado como %s.", loggedInUsername)
                );
            } else {
                showInfo(
                    I18n.tr("Sesión iniciada"),
                    I18n.tr("La cuenta de osu! quedó vinculada correctamente.")
                );
            }
        });

        loginTask.setOnFailed(evt -> {
            Throwable ex = loginTask.getException();
            disableLoginControls(false);
            showError(
                I18n.tr("No se pudo iniciar sesión"),
                ex == null ? I18n.tr("Error desconocido") : ex.getMessage()
            );
        });

        Thread loginThread = new Thread(loginTask, "osu-login-flow");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    private void logout() {
        apiClient.clearUserSession();
        loggedInUsername = null;
        updateLoginControls();
        showInfo(
            I18n.tr("Sesión cerrada"),
            I18n.tr("Se eliminó la sesión de osu!.\nPuedes volver a conectarte cuando quieras.")
        );
    }

    private void fetchCurrentUserNameIfNeeded() {
        if (!apiClient.hasUserSession() || loggedInUsername != null) {
            return;
        }
        searchExecutor.submit(() -> {
            try {
                OsuApiClient.OsuUser user = apiClient.fetchCurrentUser();
                if (user != null) {
                    loggedInUsername = user.username();
                    Platform.runLater(this::updateLoginControls);
                }
            } catch (IOException ignored) {}
        });
    }

    private void updateLoginControls() {
        if (view == null) return;
        boolean hasSession = apiClient.hasUserSession();
        if (hasSession) {
            if (loggedInUsername != null && !loggedInUsername.isBlank()) {
                view.loginStatusLabel().setText(I18n.trf("Sesión iniciada como %s", loggedInUsername));
            } else {
                view.loginStatusLabel().setText(I18n.tr("Sesión de osu! activa."));
            }
        } else {
            view.loginStatusLabel().setText(I18n.tr("Sin sesión de osu!"));
        }
        view.loginButton().setDisable(hasSession);
        view.logoutButton().setDisable(!hasSession);
    }

    private void disableLoginControls(boolean running) {
        view.loginButton().setDisable(running);
        view.logoutButton().setDisable(running);
    }

    private boolean openOsuProfileInBrowser(boolean showErrors) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(OSU_OAUTH_URL));
                    return true;
                }
            }
            if (showErrors) {
                showError(
                    I18n.tr("Navegador no disponible"),
                    I18n.tr("No pudimos abrir tu navegador predeterminado automáticamente.")
                );
            }
        } catch (IOException | SecurityException | UnsupportedOperationException ex) {
            if (showErrors) {
                showError(I18n.tr("No se pudo abrir el navegador"), ex.getMessage());
            }
        }
        return false;
    }

    private void applyThemeToScene(Scene scene) {
        ThemeHelper.applyTheme(scene, currentTheme);
    }

    private void applyIcons(Stage target) {
        if (target == null) {
            return;
        }
        if (!iconImages.isEmpty()) {
            target.getIcons().setAll(iconImages);
            return;
        }
        Window owner = target.getOwner();
        if (owner instanceof Stage ownerStage && !ownerStage.getIcons().isEmpty()) {
            target.getIcons().setAll(ownerStage.getIcons());
        }
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle(title);
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle(title);
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.showAndWait();
    }

    private void configurePreviewControls() {
        previewPlayer = new BeatmapPreviewPlayer(this::updatePreviewStateUI);
        double initialVolume = clampVolume(configManager.getPreviewVolume());
        view.previewVolumeSlider().setDisable(false);
        view.previewVolumeSlider().setValue(initialVolume * 100d);
        previewPlayer.setVolume(initialVolume);
        view.previewVolumeSlider().valueProperty().addListener((obs, oldVal, newVal) -> {
            double value = clampVolume(newVal.doubleValue() / 100d);
            previewPlayer.setVolume(value);
            configManager.setPreviewVolume(value);
        });
        view.previewPlayButton().setText(ICON_PLAY);
        view.previewPlayButton().setOnAction(e -> {
            OsuApiClient.BeatmapsetSummary selection = view.resultsView().getSelectionModel().getSelectedItem();
            if (selection != null) {
                previewPlayer.toggle(selection.id());
            }
        });
        view.resultsView().getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> handlePreviewSelectionChanged(newVal));
        handlePreviewSelectionChanged(null);
    }

    private void handlePreviewSelectionChanged(OsuApiClient.BeatmapsetSummary summary) {
        selectedPreview = summary;
        if (summary == null) {
            view.downloadButton().setDisable(true);
            lastPreviewStatsText = "";
            updatePreviewImage(null);
            view.previewTitleLabel().setText(I18n.tr("Selecciona un beatmap"));
            view.previewMapperLabel().setText("");
            view.previewPlayButton().setDisable(true);
            view.previewPlayButton().setText(ICON_PLAY);
            refreshPreviewStatusLabel("");
            if (previewPlayer != null) {
                previewPlayer.stop();
            }
            return;
        }

        view.downloadButton().setDisable(false);
        view.previewPlayButton().setDisable(false);
        updatePreviewMetadata(summary);
        final BeatmapPreviewPlayer player = previewPlayer;
        if (player == null) {
            view.previewPlayButton().setText(ICON_PLAY);
            return;
        }
        long currentId = player.getCurrentBeatmapsetId();
        if (currentId != summary.id()) {
            player.stop();
            view.previewPlayButton().setText(ICON_PLAY);
        } else {
            updatePreviewStateUI(player.getCurrentState());
        }
    }

    private void updatePreviewMetadata(OsuApiClient.BeatmapsetSummary summary) {
        view.previewTitleLabel().setText(summary.title());
        view.previewMapperLabel().setText(summary.artist() + " • " + summary.creator());
        updatePreviewImage(summary.coverUrl());
        lastPreviewStatsText = buildStatsText(summary);
        refreshPreviewStatusLabel("");
    }

    private void updatePreviewImage(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) {
            view.previewImageView().setImage(null);
            return;
        }
        try {
            view.previewImageView().setImage(new Image(coverUrl, true));
        } catch (IllegalArgumentException ex) {
            view.previewImageView().setImage(null);
        }
    }

    private void updatePreviewStateUI(BeatmapPreviewPlayer.State state) {
        if (view == null) {
            return;
        }
        switch (state) {
            case LOADING -> {
                view.previewPlayButton().setText(ICON_LOADING);
                view.previewPlayButton().setDisable(true);
                refreshPreviewStatusLabel(I18n.tr("Cargando preview..."));
            }
            case PLAYING -> {
                view.previewPlayButton().setText(ICON_PAUSE);
                view.previewPlayButton().setDisable(false);
                refreshPreviewStatusLabel(I18n.tr("Reproduciendo preview"));
            }
            case PAUSED -> {
                view.previewPlayButton().setText(ICON_PLAY);
                view.previewPlayButton().setDisable(selectedPreview == null);
                refreshPreviewStatusLabel(I18n.tr("Preview en pausa"));
            }
            case ERROR -> {
                view.previewPlayButton().setText(ICON_RETRY);
                view.previewPlayButton().setDisable(selectedPreview == null);
                refreshPreviewStatusLabel(I18n.tr("No se pudo reproducir la preview. Inténtalo de nuevo."));
            }
            case IDLE -> {
                view.previewPlayButton().setText(ICON_PLAY);
                view.previewPlayButton().setDisable(selectedPreview == null);
                refreshPreviewStatusLabel("");
            }
        }
    }

    private void refreshLanguageDependentTexts() {
        updateLoginControls();
        if (view == null) {
            return;
        }
        if (selectedPreview == null) {
            view.previewTitleLabel().setText(I18n.tr("Selecciona un beatmap"));
            refreshPreviewStatusLabel("");
            return;
        }
        lastPreviewStatsText = buildStatsText(selectedPreview);
        BeatmapPreviewPlayer player = previewPlayer;
        if (player != null) {
            updatePreviewStateUI(player.getCurrentState());
        } else {
            refreshPreviewStatusLabel("");
        }
    }

    private void refreshPreviewStatusLabel(String playbackMessage) {
        if (view == null) {
            return;
        }
        if (selectedPreview == null) {
            view.previewStatusLabel().setText(I18n.tr(DEFAULT_PREVIEW_MESSAGE_KEY));
            return;
        }
        if (playbackMessage == null || playbackMessage.isBlank()) {
            view.previewStatusLabel().setText(lastPreviewStatsText);
        } else if (lastPreviewStatsText == null || lastPreviewStatsText.isBlank()) {
            view.previewStatusLabel().setText(playbackMessage);
        } else {
            view.previewStatusLabel().setText(lastPreviewStatsText + "\n" + playbackMessage);
        }
    }

    private String buildStatsText(OsuApiClient.BeatmapsetSummary summary) {
        StringBuilder builder = new StringBuilder();
        builder.append(I18n.tr("Estado:")).append(' ').append(prettifyStatus(summary.status()));
        if (summary.bpm() > 0d) {
            builder.append(" • ").append(Math.round(summary.bpm())).append(" BPM");
        }
        builder.append(" • ").append(I18n.tr(summary.video() ? "Con video" : "Sin video"));
        builder.append("\n❤ ").append(integerFormat.format(Math.max(0, summary.favouriteCount())));
        builder.append(" • ▶ ").append(integerFormat.format(Math.max(0, summary.playCount())));
        return builder.toString();
    }

    private static String prettifyStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return I18n.tr("Desconocido");
        }
        return switch (rawStatus.toLowerCase()) {
            case "ranked" -> "Ranked";
            case "loved" -> "Loved";
            case "qualified" -> "Qualified";
            case "pending" -> "Pending";
            case "graveyard" -> "Graveyard";
            case "wip" -> "Work in progress";
            default -> rawStatus;
        };
    }

    private static double clampVolume(double volume) {
        if (volume < 0d) return 0d;
        if (volume > 1d) return 1d;
        return volume;
    }

    private void handleOfficialControlsVisibilityChanged(boolean visible) {
        if (stage == null) {
            pendingOfficialVisibility = visible;
            return;
        }
        pendingOfficialVisibility = null;
        if (visible) {
            lastNonOfficialHeight = Math.max(BASE_DIALOG_HEIGHT, stage.getHeight());
            double targetHeight = Math.max(lastNonOfficialHeight, BASE_DIALOG_HEIGHT + OFFICIAL_HEIGHT_EXTRA);
            stage.setMinHeight(BASE_DIALOG_HEIGHT + OFFICIAL_HEIGHT_EXTRA);
            stage.setHeight(targetHeight);
        } else {
            stage.setMinHeight(BASE_DIALOG_HEIGHT);
            double targetHeight = Math.max(BASE_DIALOG_HEIGHT, lastNonOfficialHeight);
            stage.setHeight(targetHeight);
        }
    }

    private void applyPendingOfficialVisibility() {
        if (pendingOfficialVisibility != null) {
            boolean visible = pendingOfficialVisibility;
            pendingOfficialVisibility = null;
            handleOfficialControlsVisibilityChanged(visible);
        }
    }

    private String safeText(javafx.scene.control.TextField field) {
        if (field == null) {
            return "";
        }
        String text = field.getText();
        return text == null ? "" : text.trim();
    }

    private final DialogCallbacks dialogCallbacks = new DialogCallbacks() {
        @Override
        public void showInfo(String title, String message) {
            Platform.runLater(() -> OsuBeatmapBrowserDialog.this.showInfo(title, message));
        }

        @Override
        public void showError(String title, String message) {
            Platform.runLater(() -> OsuBeatmapBrowserDialog.this.showError(title, message));
        }
    };
}
