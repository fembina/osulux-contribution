package com.osuplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javafx.application.Application;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

public class MainApp extends Application {

    private static MediaPlayerFactory factory;
    private static EmbeddedMediaPlayer audioPlayer;
    private static EmbeddedMediaPlayer videoPlayer;
    private static DiscordRichPresence discord;

    private ConfigManager configManager;
    private MusicManager musicManager;

    static {
        Path baseDir = Paths.get("").toAbsolutePath();
        Path libDir = baseDir.resolve("lib");
        Path libsDir = baseDir.resolve("libs");

        Path nativeDir = Files.isDirectory(libDir) ? libDir : libsDir;
        System.setProperty("jna.library.path", nativeDir.toString());

        Path pluginDir = nativeDir.resolve("plugins");
        if (!Files.isDirectory(pluginDir) && Files.isDirectory(libsDir.resolve("plugins"))) {
            pluginDir = libsDir.resolve("plugins");
        }
        System.setProperty("VLC_PLUGIN_PATH", pluginDir.toString());

    }

    @Override
    public void start(Stage primaryStage) {
        configManager = new ConfigManager();
        musicManager = new MusicManager();

        UIController ui = new UIController(audioPlayer, videoPlayer, configManager, musicManager, discord);
        ui.start(primaryStage);

        primaryStage.setOnCloseRequest(event -> {
            if (discord != null) {
                discord.stop();
            }
            if (audioPlayer != null) {
                audioPlayer.release();
            }
            if (videoPlayer != null) {
                videoPlayer.release();
            }
            if (factory != null) {
                factory.release();
            }
            System.exit(0);
        });
    }

    public static void main(String[] args) {
        factory = new MediaPlayerFactory("--input-title-format=Osulux");
        audioPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
        videoPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();

        discord = new DiscordRichPresence();
        discord.start(1418149866168909846L);

        launch(args);
    }
}
