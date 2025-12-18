package com.osuplayer.beatmapbrowser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import com.osuplayer.config.ConfigManager;
import com.osuplayer.lang.I18n;
import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.lang.LanguageManager;
import com.osuplayer.mirrors.MirrorServer;
import com.osuplayer.osu.OsuApiClient;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

public class BeatmapBrowserView {

    private static final String DEFAULT_STATUS_MESSAGE_KEY = "Ingresa un término de búsqueda.";

    private final BorderPane root = new BorderPane();
    private final LanguageManager languageManager = LanguageManager.getInstance();

    private TextField clientIdField;
    private PasswordField clientSecretField;
    private Label supporterInfoLabel;
    private Button credentialHelperButton;
    private HBox credentialHelperContainer;
    private Label loginStatusLabel;
    private Button loginButton;
    private Button logoutButton;
    private GridPane credentialsGrid;
    private HBox loginActionsBox;
    private Button saveCredentialsButton;

    private TextField queryField;
    private ComboBox<OsuApiClient.BeatmapMode> modeCombo;
    private ComboBox<OsuApiClient.BeatmapStatus> statusCombo;
    private ComboBox<RelevanceSortOption> relevanceCombo;
    private ComboBox<VideoFilterOption> videoFilterCombo;
    private ComboBox<SourceOption> sourceCombo;
    private CheckBox includeVideoCheck;
    private CheckBox autoRefreshCheck;
    private Button searchButton;

    private Button previousButton;
    private Button nextButton;
    private Label statusLabel;
    private Node progressIndicator;
    private Button downloadButton;
    private Supplier<String> statusMessageSupplier = () -> I18n.tr(DEFAULT_STATUS_MESSAGE_KEY);

    private final ListView<OsuApiClient.BeatmapsetSummary> resultsView;
    private ImageView previewImageView;
    private Label previewTitleLabel;
    private Label previewMapperLabel;
    private Label previewStatusLabel;
    private Button previewPlayButton;
    private Slider previewVolumeSlider;
    private Consumer<Boolean> officialControlsListener;

    public BeatmapBrowserView(ConfigManager configManager,
                              ObservableList<OsuApiClient.BeatmapsetSummary> currentResults,
                              List<MirrorServer> mirrorServers) {
        root.setPadding(new Insets(15));

        VBox topBox = buildCredentialsSection(configManager);
        VBox searchBox = buildSearchSection(mirrorServers);
        topBox.getChildren().add(searchBox);
        root.setTop(topBox);

        this.resultsView = new ListView<>(currentResults);
        resultsView.setMinWidth(520);
        resultsView.setPrefWidth(680);
        VBox.setVgrow(resultsView, Priority.ALWAYS);

        VBox previewPanel = buildPreviewPanel();
        VBox resultsColumn = new VBox(12);
        resultsColumn.setAlignment(Pos.TOP_LEFT);

        BorderPane bottomSection = buildBottomSection();
        resultsColumn.getChildren().addAll(resultsView, bottomSection);
        VBox.setVgrow(bottomSection, Priority.NEVER);

        HBox centerBox = new HBox(20, resultsColumn, previewPanel);
        centerBox.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(resultsColumn, Priority.ALWAYS);
        HBox.setHgrow(previewPanel, Priority.SOMETIMES);
        root.setCenter(centerBox);

        languageManager.languageIdProperty().addListener((obs, oldId, newId) ->
            Platform.runLater(() -> {
                refreshStatusMessage();
                resultsView.refresh();
            })
        );
    }

    public BorderPane root() {
        return root;
    }

    public void configureResultsList(LongFunction<DoubleProperty> progressProvider) {
        resultsView.setCellFactory(list -> new BeatmapCell(progressProvider));
    }

    public TextField clientIdField() { return clientIdField; }
    public PasswordField clientSecretField() { return clientSecretField; }
    public Label supporterInfoLabel() { return supporterInfoLabel; }
    public Button credentialHelperButton() { return credentialHelperButton; }
    public HBox credentialHelperContainer() { return credentialHelperContainer; }
    public Label loginStatusLabel() { return loginStatusLabel; }
    public Button loginButton() { return loginButton; }
    public Button logoutButton() { return logoutButton; }
    public GridPane credentialsGrid() { return credentialsGrid; }
    public HBox loginActionsBox() { return loginActionsBox; }
    public Button saveCredentialsButton() { return saveCredentialsButton; }

    public TextField queryField() { return queryField; }
    public ComboBox<OsuApiClient.BeatmapMode> modeCombo() { return modeCombo; }
    public ComboBox<OsuApiClient.BeatmapStatus> statusCombo() { return statusCombo; }
    public ComboBox<RelevanceSortOption> relevanceCombo() { return relevanceCombo; }
    public ComboBox<VideoFilterOption> videoFilterCombo() { return videoFilterCombo; }
    public ComboBox<SourceOption> sourceCombo() { return sourceCombo; }
    public CheckBox includeVideoCheck() { return includeVideoCheck; }
    public CheckBox autoRefreshCheck() { return autoRefreshCheck; }
    public Button searchButton() { return searchButton; }

    public Button previousButton() { return previousButton; }
    public Button nextButton() { return nextButton; }
    public Label statusLabel() { return statusLabel; }
    public Node progressIndicator() { return progressIndicator; }
    public Button downloadButton() { return downloadButton; }
    public ListView<OsuApiClient.BeatmapsetSummary> resultsView() { return resultsView; }
    public ImageView previewImageView() { return previewImageView; }
    public Label previewTitleLabel() { return previewTitleLabel; }
    public Label previewMapperLabel() { return previewMapperLabel; }
    public Label previewStatusLabel() { return previewStatusLabel; }
    public Button previewPlayButton() { return previewPlayButton; }
    public Slider previewVolumeSlider() { return previewVolumeSlider; }

    public void showStatusMessage(Supplier<String> supplier) {
        statusMessageSupplier = supplier == null ? () -> "" : supplier;
        refreshStatusMessage();
    }

    public void resetStatusMessage() {
        statusMessageSupplier = () -> I18n.tr(DEFAULT_STATUS_MESSAGE_KEY);
        refreshStatusMessage();
    }

    public void refreshLanguageNow() {
        refreshStatusMessage();
        resultsView.refresh();
    }

    private void refreshStatusMessage() {
        if (statusLabel != null && statusMessageSupplier != null) {
            statusLabel.setText(statusMessageSupplier.get());
        }
    }

    public void toggleOfficialControls(boolean visible) {
        credentialsGrid.setManaged(visible);
        credentialsGrid.setVisible(visible);
        loginActionsBox.setManaged(visible);
        loginActionsBox.setVisible(visible);
        loginStatusLabel.setManaged(visible);
        loginStatusLabel.setVisible(visible);
        supporterInfoLabel.setManaged(visible);
        supporterInfoLabel.setVisible(visible);
        credentialHelperContainer.setManaged(visible);
        credentialHelperContainer.setVisible(visible);
        if (officialControlsListener != null) {
            officialControlsListener.accept(visible);
        }
    }

    public void setOfficialControlsVisibilityListener(Consumer<Boolean> listener) {
        this.officialControlsListener = listener;
    }

    private VBox buildCredentialsSection(ConfigManager configManager) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(0, 0, 10, 0));

        Label info = new Label();
        LanguageBindings.bindLabeled(info, "Puedes usar la API oficial de osu! si introduces client_id y client_secret. También puedes elegir mirrors públicos para buscar y descargar sin iniciar sesión.");
        info.setWrapText(true);

        this.credentialsGrid = new GridPane();
        credentialsGrid.setHgap(10);
        credentialsGrid.setVgap(10);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(25);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(75);
        credentialsGrid.getColumnConstraints().addAll(c1, c2);

        this.clientIdField = new TextField(configManager.getOsuClientId());
        LanguageBindings.bindPrompt(clientIdField, "client_id");
        this.clientSecretField = new PasswordField();
        LanguageBindings.bindPrompt(clientSecretField, "client_secret");
        clientSecretField.setText(configManager.getOsuClientSecret());

        this.saveCredentialsButton = new Button();
        LanguageBindings.bindLabeled(saveCredentialsButton, "Guardar credenciales");

        Label clientIdLabel = new Label();
        LanguageBindings.bindLabeled(clientIdLabel, "Client ID:");
        Label clientSecretLabel = new Label();
        LanguageBindings.bindLabeled(clientSecretLabel, "Client secret:");
        credentialsGrid.addRow(0, clientIdLabel, clientIdField);
        credentialsGrid.addRow(1, clientSecretLabel, clientSecretField);
        credentialsGrid.add(saveCredentialsButton, 1, 2);

        this.supporterInfoLabel = new Label();
        LanguageBindings.bindLabeled(supporterInfoLabel, "Debes configurar OAuth y ser supporter para descargar con la API oficial. Usa el botón de Mostrar credenciales para seguir los pasos.");
        supporterInfoLabel.setWrapText(true);
        supporterInfoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px;");

        this.credentialHelperButton = new Button();
        LanguageBindings.bindLabeled(credentialHelperButton, "Mostrar credenciales");
        credentialHelperButton.setPrefWidth(170);
        credentialHelperButton.setMaxWidth(Region.USE_PREF_SIZE);
        this.credentialHelperContainer = new HBox(credentialHelperButton);
        credentialHelperContainer.setAlignment(Pos.CENTER);

        this.loginStatusLabel = new Label();
        loginStatusLabel.setWrapText(true);
        this.loginButton = new Button();
        LanguageBindings.bindLabeled(loginButton, "Iniciar sesión con osu!");
        this.logoutButton = new Button();
        LanguageBindings.bindLabeled(logoutButton, "Cerrar sesión");
        this.loginActionsBox = new HBox(10, loginButton, logoutButton);
        loginActionsBox.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(
            info,
            credentialsGrid,
            supporterInfoLabel,
            credentialHelperContainer,
            loginStatusLabel,
            loginActionsBox
        );
        return box;
    }

    private VBox buildSearchSection(List<MirrorServer> mirrorServers) {
        VBox box = new VBox(10);

        HBox searchRow = new HBox(10);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        this.queryField = new TextField();
        LanguageBindings.bindPrompt(queryField, "Busca por título, artista, mapper o tags...");
        HBox.setHgrow(queryField, Priority.ALWAYS);

        this.modeCombo = new ComboBox<>();
        modeCombo.getItems().setAll(OsuApiClient.BeatmapMode.values());
        modeCombo.getSelectionModel().select(OsuApiClient.BeatmapMode.ANY);

        this.statusCombo = new ComboBox<>();
        statusCombo.getItems().setAll(OsuApiClient.BeatmapStatus.values());
        statusCombo.getSelectionModel().select(OsuApiClient.BeatmapStatus.ANY);

        this.relevanceCombo = new ComboBox<>();
        relevanceCombo.getItems().setAll(RelevanceSortOption.values());
        relevanceCombo.getSelectionModel().select(RelevanceSortOption.MOST_RELEVANT);

        this.videoFilterCombo = new ComboBox<>();
        videoFilterCombo.getItems().setAll(VideoFilterOption.values());
        videoFilterCombo.getSelectionModel().select(VideoFilterOption.ALL);

        this.sourceCombo = new ComboBox<>();
        populateSourceOptions(mirrorServers);
        sourceCombo.getSelectionModel().select(Math.min(1, sourceCombo.getItems().size() - 1));

        this.includeVideoCheck = new CheckBox();
        LanguageBindings.bindLabeled(includeVideoCheck, "Descargar con video (si existe)");
        this.autoRefreshCheck = new CheckBox();
        LanguageBindings.bindLabeled(autoRefreshCheck, "Actualizar biblioteca automáticamente después de importar");

        this.searchButton = new Button();
        LanguageBindings.bindLabeled(searchButton, "Buscar");

        searchRow.getChildren().addAll(boundLabel("Buscar:"), queryField,
            boundLabel("Modo:"), modeCombo,
            boundLabel("Estado:"), statusCombo,
            boundLabel("Relevancia:"), relevanceCombo,
            boundLabel("Video:"), videoFilterCombo,
            boundLabel("Fuente:"), sourceCombo,
            searchButton);

        languageManager.languageIdProperty().addListener((obs, oldId, newId) -> {
            refreshComboLabels(modeCombo);
            refreshComboLabels(statusCombo);
            refreshComboLabels(relevanceCombo);
            refreshComboLabels(videoFilterCombo);
            refreshComboLabels(sourceCombo);
        });

        box.getChildren().addAll(searchRow, includeVideoCheck, autoRefreshCheck);
        return box;
    }

    private void populateSourceOptions(List<MirrorServer> mirrorServers) {
        List<SourceOption> options = new ArrayList<>();
        options.add(new SourceOption("API oficial", true, false, null));
        options.add(new SourceOption("Mirrors (auto)", false, true, null));
        for (MirrorServer server : mirrorServers) {
            options.add(new SourceOption(server.displayName(), false, false, server, false));
        }
        sourceCombo.getItems().setAll(options);
    }

    private <T> void refreshComboLabels(ComboBox<T> comboBox) {
        if (comboBox == null) {
            return;
        }
        T selected = comboBox.getValue();
        ObservableList<T> refreshed = FXCollections.observableArrayList(comboBox.getItems());
        comboBox.setItems(refreshed);
        comboBox.getSelectionModel().select(selected);
    }

    private BorderPane buildBottomSection() {
        BorderPane bottomRow = new BorderPane();
        bottomRow.setPadding(new Insets(10, 0, 0, 0));
        bottomRow.setMinHeight(72);

        this.previousButton = new Button();
        LanguageBindings.bindLabeled(previousButton, "← Anterior");
        previousButton.setDisable(true);
        this.nextButton = new Button();
        LanguageBindings.bindLabeled(nextButton, "Siguiente →");
        nextButton.setDisable(true);

        HBox navigationBox = new HBox(8, previousButton, nextButton);
        navigationBox.setAlignment(Pos.CENTER_RIGHT);
        BorderPane.setAlignment(navigationBox, Pos.CENTER_RIGHT);
        HBox.setMargin(previousButton, Insets.EMPTY);

        this.statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setTextAlignment(TextAlignment.CENTER);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        this.progressIndicator = com.osuplayer.ui.LoadingAnimationFactory.createLoader(36);
        progressIndicator.setVisible(false);
        progressIndicator.managedProperty().bind(progressIndicator.visibleProperty());

        HBox statusContainer = new HBox(12, progressIndicator, statusLabel);
        statusContainer.setAlignment(Pos.CENTER);
        statusContainer.setPadding(new Insets(0, 12, 0, 12));
        statusContainer.setMinWidth(260);
        statusContainer.setPrefWidth(320);
        statusContainer.setMaxWidth(380);

        this.downloadButton = new Button();
        LanguageBindings.bindLabeled(downloadButton, "Descargar selección");
        downloadButton.setDisable(true);
        HBox downloadBox = new HBox(downloadButton);
        downloadBox.setAlignment(Pos.CENTER_LEFT);

        bottomRow.setLeft(downloadBox);
        bottomRow.setCenter(statusContainer);
        bottomRow.setRight(navigationBox);
        resetStatusMessage();
        return bottomRow;
    }

    private VBox buildPreviewPanel() {
        VBox panel = new VBox(10);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setPrefWidth(420);
        panel.setMinWidth(360);
        panel.setMaxWidth(Double.MAX_VALUE);

        previewImageView = new ImageView();
        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);
        previewImageView.fitWidthProperty().bind(panel.widthProperty().subtract(20));
        previewImageView.fitHeightProperty().bind(Bindings.createDoubleBinding(
            () -> Math.max(120d, panel.getHeight() - 180d),
            panel.heightProperty()));
        VBox.setVgrow(previewImageView, Priority.ALWAYS);

        previewTitleLabel = new Label(I18n.tr("Selecciona un beatmap"));
        previewTitleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        previewTitleLabel.setWrapText(true);

        previewMapperLabel = new Label();
        previewMapperLabel.setWrapText(true);

        previewPlayButton = new Button("▶");
        previewPlayButton.setDisable(true);
        previewPlayButton.setMinSize(36, 36);
        previewPlayButton.setPrefSize(40, 40);
        previewPlayButton.setFocusTraversable(false);

        previewVolumeSlider = new Slider(0, 100, 70);
        previewVolumeSlider.setDisable(true);
        previewVolumeSlider.setMinWidth(140);
        previewVolumeSlider.setPrefWidth(180);
        previewVolumeSlider.setMaxWidth(240);

        HBox previewControls = new HBox(10, previewPlayButton, previewVolumeSlider);
        previewControls.setAlignment(Pos.CENTER);

        previewStatusLabel = new Label(I18n.tr("Selecciona una canción para ver la carátula y reproducir una preview."));
        previewStatusLabel.setWrapText(true);
        previewStatusLabel.setStyle("-fx-font-size: 11px;");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        panel.getChildren().addAll(previewImageView,
                previewTitleLabel,
                previewMapperLabel,
                previewControls,
                spacer,
                previewStatusLabel);
        return panel;
    }

    private Label boundLabel(String spanish) {
        Label label = new Label();
        LanguageBindings.bindLabeled(label, spanish);
        return label;
    }

}
