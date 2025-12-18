package com.osuplayer.beatmapbrowser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.osuplayer.lang.LanguageBindings;
import com.osuplayer.osu.OsuAuthorizationHelper;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

public class CredentialHelperManager {

    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("\\b(\\d{4,})\\b");
    private static final Pattern CLIENT_SECRET_PATTERN = Pattern.compile("\\b([A-Za-z0-9_-]{16,})\\b");

    private final BeatmapBrowserView view;
    private final OsuAuthorizationHelper authorizationHelper;
    private final java.util.function.Supplier<Stage> ownerSupplier;
    private final java.util.function.Consumer<Scene> themeApplier;
    private final java.util.function.Consumer<Stage> iconApplier;
    private final BrowserLauncher browserLauncher;

    private Stage helperStage;
    private Timeline clipboardWatcher;
    private String lastClipboardSample = "";

    public CredentialHelperManager(BeatmapBrowserView view,
                                   OsuAuthorizationHelper authorizationHelper,
                                   java.util.function.Supplier<Stage> ownerSupplier,
                                   java.util.function.Consumer<Scene> themeApplier,
                                   java.util.function.Consumer<Stage> iconApplier,
                                   BrowserLauncher browserLauncher) {
        this.view = view;
        this.authorizationHelper = authorizationHelper;
        this.ownerSupplier = ownerSupplier;
        this.themeApplier = themeApplier;
        this.iconApplier = iconApplier;
        this.browserLauncher = browserLauncher;
    }

    public void start() {
        browserLauncher.open(true);
        ensureStage();
        helperStage.show();
        helperStage.toFront();
        startClipboardWatcher();
    }

    public void hide() {
        stopClipboardWatcher();
        if (helperStage != null) {
            helperStage.hide();
        }
    }

    public void shutdown() {
        stopClipboardWatcher();
        if (helperStage != null) {
            helperStage.close();
            helperStage = null;
        }
    }

    private void ensureStage() {
        if (helperStage != null) {
            return;
        }
        helperStage = new Stage();
        Stage owner = ownerSupplier.get();
        if (owner != null) {
            helperStage.initOwner(owner);
        }
        helperStage.initModality(Modality.NONE);
        helperStage.setTitle("Detectar credenciales de osu!");
        LanguageBindings.bindStageTitle(helperStage, "Detectar credenciales de osu!");
        iconApplier.accept(helperStage);

        String redirectUri = authorizationHelper.getRedirectUri();

        Label stepsHeader = new Label("Pasos para configurar la API oficial:");
        LanguageBindings.bindLabeled(stepsHeader, "Pasos para configurar la API oficial:");
        stepsHeader.setStyle("-fx-font-weight: bold;");

        Label step1 = new Label("1. Pulsa \"New OAuth application\" en la página que abrimos.");
        LanguageBindings.bindLabeled(step1, "1. Pulsa \"New OAuth application\" en la página que abrimos.");
        step1.setWrapText(true);

        HBox nameRow = buildCopyRow("2. Nombre recomendado", "Osulux");
        HBox redirectRow = buildCopyRow("3. Redirect URI obligatoria", redirectUri);

        Label step4 = new Label("4. Guarda la app, copia el Client ID y el Client secret (Ctrl+C). Osulux los pegará automáticamente en los campos de arriba.");
        LanguageBindings.bindLabeled(step4, "4. Guarda la app, copia el Client ID y el Client secret (Ctrl+C). Osulux los pegará automáticamente en los campos de arriba.");
        step4.setWrapText(true);

        Button reopenButton = new Button("Abrir de nuevo");
        LanguageBindings.bindLabeled(reopenButton, "Abrir de nuevo");
        reopenButton.setOnAction(e -> browserLauncher.open(false));
        Button stopButton = new Button("Detener detección");
        LanguageBindings.bindLabeled(stopButton, "Detener detección");
        stopButton.setOnAction(e -> stopClipboardWatcher());
        Button closeButton = new Button("Cerrar");
        LanguageBindings.bindLabeled(closeButton, "Cerrar");
        closeButton.setOnAction(e -> {
            stopClipboardWatcher();
            helperStage.hide();
        });
        HBox controls = new HBox(10, reopenButton, stopButton, closeButton);
        controls.setAlignment(Pos.CENTER_RIGHT);

        VBox container = new VBox(12,
            stepsHeader,
            step1,
            nameRow,
            redirectRow,
            step4,
            controls);
        container.setPadding(new Insets(12));

        Scene helperScene = new Scene(container, 760, 320);
        themeApplier.accept(helperScene);
        helperStage.setScene(helperScene);
        helperStage.setOnCloseRequest(evt -> stopClipboardWatcher());
    }

    private HBox buildCopyRow(String labelText, String value) {
        Label label = new Label(labelText + ":");
        LanguageBindings.bindLabeled(label, labelText + ":");
        TextField field = new TextField(value);
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setOnMouseClicked(e -> copy(value));

        Button copyButton = new Button("Copiar");
        LanguageBindings.bindLabeled(copyButton, "Copiar");
        copyButton.setOnAction(e -> copy(value));

        HBox row = new HBox(8, label, field, copyButton);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private void copy(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        clipboard.setContent(content);
    }

    private void startClipboardWatcher() {
        stopClipboardWatcher();
        lastClipboardSample = "";
        clipboardWatcher = new Timeline(new KeyFrame(Duration.millis(850), e -> pollClipboard()));
        clipboardWatcher.setCycleCount(Timeline.INDEFINITE);
        clipboardWatcher.play();
    }

    private void stopClipboardWatcher() {
        if (clipboardWatcher != null) {
            clipboardWatcher.stop();
            clipboardWatcher = null;
        }
    }

    private void pollClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard == null || !clipboard.hasString()) {
            return;
        }
        String text = clipboard.getString();
        if (text == null || text.isBlank() || text.equals(lastClipboardSample)) {
            return;
        }
        lastClipboardSample = text;
        CredentialCandidate candidate = parseCredentials(text);
        boolean updated = false;
        if (candidate.clientId() != null && !candidate.clientId().isBlank()) {
            String current = safeText(view.clientIdField());
            if (current.isBlank() || !current.equals(candidate.clientId())) {
                view.clientIdField().setText(candidate.clientId());
                updated = true;
            }
        }
        if (candidate.clientSecret() != null && !candidate.clientSecret().isBlank()) {
            String currentSecret = safeText(view.clientSecretField());
            if (currentSecret.isBlank() || !currentSecret.equals(candidate.clientSecret())) {
                view.clientSecretField().setText(candidate.clientSecret());
                updated = true;
            }
        }
        if (updated && !safeText(view.clientIdField()).isBlank() && !safeText(view.clientSecretField()).isBlank()) {
            stopClipboardWatcher();
        }
    }

    private CredentialCandidate parseCredentials(String text) {
        if (text == null) {
            return new CredentialCandidate(null, null);
        }
        String clientId = null;
        String clientSecret = null;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (clientId == null && lower.contains("client") && lower.contains("id")) {
                clientId = findClientId(trimmed);
            }
            if (clientSecret == null && lower.contains("secret")) {
                clientSecret = findClientSecret(trimmed);
            }
        }
        if (clientId == null) {
            clientId = findClientId(text);
        }
        if (clientSecret == null) {
            clientSecret = findClientSecret(text);
        }
        return new CredentialCandidate(clientId, clientSecret);
    }

    private String findClientId(String text) {
        if (text == null) return null;
        Matcher matcher = CLIENT_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.length() <= 12) {
                return value;
            }
        }
        return null;
    }

    private String findClientSecret(String text) {
        if (text == null) return null;
        Matcher matcher = CLIENT_SECRET_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (hasLettersAndDigits(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasLettersAndDigits(String value) {
        if (value == null) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char ch : value.toCharArray()) {
            if (Character.isLetter(ch)) {
                hasLetter = true;
            } else if (Character.isDigit(ch)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }

    private String safeText(TextField field) {
        if (field == null) {
            return "";
        }
        String value = field.getText();
        return value == null ? "" : value.trim();
    }

    private record CredentialCandidate(String clientId, String clientSecret) { }

    @FunctionalInterface
    public interface BrowserLauncher {
        boolean open(boolean showErrors);
    }
}
