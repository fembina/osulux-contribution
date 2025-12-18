package com.osuplayer.app;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.osuplayer.config.ConfigManager;
import com.osuplayer.discord.DiscordRichPresence;
import com.osuplayer.lang.LanguageManager;
import com.osuplayer.lang.LanguageSelectionDialog;
import com.osuplayer.playback.MusicManager;
import com.osuplayer.ui.UIController;

import javafx.application.Application;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

public class MainApp extends Application {


    private static MediaPlayerFactory factory;
    private static EmbeddedMediaPlayer audioPlayer;
    private static EmbeddedMediaPlayer videoPlayer;
    private static DiscordRichPresence discord;

    private static Path libDirRef;

    private ConfigManager configManager;
    private MusicManager musicManager;

    static {
        try {
            File jarFile = new File(MainApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path baseDir = jarFile.getParentFile().toPath();
            Path resolvedLibDir = findLibDir(baseDir);
            Path resolvedPluginDir = findPluginDir(resolvedLibDir, baseDir);
            libDirRef = resolvedLibDir;

            if (resolvedLibDir != null && Files.isDirectory(resolvedLibDir)) {
                System.setProperty("jna.library.path", resolvedLibDir.toString());
            }
            if (resolvedPluginDir != null && Files.isDirectory(resolvedPluginDir)) {
                System.setProperty("VLC_PLUGIN_PATH", resolvedPluginDir.toString());
            }
        } catch (URISyntaxException e) {
        }

    }

    private static Path findLibDir(Path baseDir) {
        if (baseDir == null) return Path.of("lib");
        Path candidate = baseDir.resolve("lib");
        if (Files.isDirectory(candidate)) return candidate;

        Path parent = baseDir.getParent();
        if (parent != null) {
            Path parentCandidate = parent.resolve("lib");
            if (Files.isDirectory(parentCandidate)) return parentCandidate;
        }

        return candidate;
    }

    private static Path findPluginDir(Path libDir, Path baseDir) {
        if (libDir != null) {
            Path candidate = libDir.resolve("plugins");
            if (Files.isDirectory(candidate)) return candidate;
        }

        if (baseDir != null) {
            Path runtimePlugins = baseDir.resolve("runtime").resolve("plugins");
            if (Files.isDirectory(runtimePlugins)) return runtimePlugins;

            Path runtimeLibPlugins = baseDir.resolve("runtime").resolve("lib").resolve("plugins");
            if (Files.isDirectory(runtimeLibPlugins)) return runtimeLibPlugins;

            Path exportadoPlugins = baseDir.resolve("Exportado").resolve("lib").resolve("plugins");
            if (Files.isDirectory(exportadoPlugins)) return exportadoPlugins;
            Path parentExportadoPlugins = baseDir.getParent() == null ? null
                : baseDir.getParent().resolve("Exportado").resolve("lib").resolve("plugins");
            if (parentExportadoPlugins != null && Files.isDirectory(parentExportadoPlugins)) return parentExportadoPlugins;
        }

        return null;
    }

    @Override
    public void start(Stage primaryStage) {
        configManager = new ConfigManager();
        musicManager = new MusicManager(configManager);

        initializeLanguage(primaryStage);

        UIController ui = new UIController(audioPlayer, videoPlayer, configManager, musicManager, discord);
        ui.start(primaryStage);

        primaryStage.setOnCloseRequest(event -> {
            ui.shutdown();
            if (discord != null) discord.stop();
            if (audioPlayer != null) audioPlayer.release();
            if (videoPlayer != null) videoPlayer.release();
            if (factory != null) factory.release();
            System.exit(0);
        });
    }

    private void initializeLanguage(Stage owner) {
        LanguageManager languageManager = LanguageManager.getInstance();
        String languageId = configManager.getLanguage();
        if (languageId == null || languageId.isBlank()) {
            LanguageSelectionDialog dialog = new LanguageSelectionDialog(owner, configManager.getTheme());
            languageId = dialog.showAndWait();
            if (languageId == null || languageId.isBlank()) languageId = "es";
            configManager.setLanguage(languageId);
        }
        languageManager.setLanguage(languageId);
        if (discord != null) discord.setIdleStatus();
    }

    public static void main(String[] args) {
        factory = createFactoryWithFallback();
        audioPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
        videoPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();

        discord = new DiscordRichPresence();
        discord.start(1418149866168909846L);

        launch(args);
    }

    private static MediaPlayerFactory createFactoryWithFallback() {
        MediaPlayerFactory mediaFactory = tryCreateFactory();
        if (mediaFactory != null) return mediaFactory;

        System.clearProperty("jna.library.path");
        System.clearProperty("VLC_PLUGIN_PATH");

        if (libDirRef != null && Files.isDirectory(libDirRef)) {
            System.setProperty("VLC_PLUGIN_PATH", libDirRef.toString());
            MediaPlayerFactory libOnlyFactory = tryCreateFactory();
            if (libOnlyFactory != null) return libOnlyFactory;
            System.clearProperty("VLC_PLUGIN_PATH");
        }

        if (new NativeDiscovery().discover()) {
            MediaPlayerFactory discoveredFactory = tryCreateFactory();
            if (discoveredFactory != null) return discoveredFactory;
        }

        throw new RuntimeException("No se pudo inicializar VLC: no se encontró libvlc operativo");
    }

    private static MediaPlayerFactory tryCreateFactory() {
        try {
            return new MediaPlayerFactory("--input-title-format=Osulux");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
