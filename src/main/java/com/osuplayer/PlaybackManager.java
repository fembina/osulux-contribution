package com.osuplayer;

import java.util.Random;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

public class PlaybackManager {

    private final EmbeddedMediaPlayer audioPlayer;
    private final ConfigManager configManager;
    private final UIController uiController;
    private final DiscordRichPresence discord;
    private final VideoSynchronizer videoSynchronizer;

    private Slider progressSlider;
    private Label timeLabel;
    private Button playPauseButton;
    private Button shuffleButton;

    private boolean isSeeking = false;
    private boolean shuffleEnabled = false;
    private final Random random = new Random();
    private long currentMediaDuration = -1;
    
    private String currentSongNameForDiscord;

    private boolean isDiscordStateSetForCurrentSong = false;

    public PlaybackManager(EmbeddedMediaPlayer audioPlayer, ConfigManager configManager, UIController uiController, DiscordRichPresence discord, VideoSynchronizer videoSynchronizer) {
        this.audioPlayer = audioPlayer;
        this.configManager = configManager;
        this.uiController = uiController;
        this.discord = discord;
        this.videoSynchronizer = videoSynchronizer;
    }

    public void initializeControls(Slider progressSlider, Label timeLabel, Slider volumeSlider, Button playPauseButton, Button shuffleButton, Button previousButton, Button stopButton, Button nextButton) {
        this.progressSlider = progressSlider;
        this.timeLabel = timeLabel;
        this.playPauseButton = playPauseButton;
        this.shuffleButton = shuffleButton;

        setupAudioPlayerListeners();
        setupUIControlListeners(volumeSlider, previousButton, stopButton, nextButton);
        updateShuffleButtonStyle();
    }

    private void setupAudioPlayerListeners() {
        audioPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                if (isSeeking) return;

                videoSynchronizer.onAudioTimeChanged(newTime);

                if (currentMediaDuration > 0) {
                    Platform.runLater(() -> {
                        progressSlider.setValue(newTime / 1000.0);
                        updateTimeLabel(newTime / 1000.0, currentMediaDuration / 1000.0);
                    });
                }
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                Platform.runLater(uiController::playNextSong);
            }

            @Override
            public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
                currentMediaDuration = newLength;
                Platform.runLater(() -> {
                    if (newLength > 0) {
                        progressSlider.setMax(newLength / 1000.0);

                        if (!isDiscordStateSetForCurrentSong) {
                            updateDiscordPresence(0, newLength);
                            isDiscordStateSetForCurrentSong = true; 
                        }
                    }
                });
            }

            @Override
            public void playing(MediaPlayer mediaPlayer) {
                videoSynchronizer.applyPlayState(true);
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                videoSynchronizer.applyPlayState(false);
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                videoSynchronizer.applyPlayState(false);
            }
        });
    }
    
    public void onNewMedia() {
        currentMediaDuration = -1;
        
        this.isDiscordStateSetForCurrentSong = false;
        videoSynchronizer.reset();
        
        Platform.runLater(() -> {
            progressSlider.setValue(0);
            progressSlider.setMax(0);
            updateTimeLabel(0, 0);
        });
    }

    private void setupUIControlListeners(Slider volumeSlider, Button previousButton, Button stopButton, Button nextButton) {
        progressSlider.setOnMousePressed(e -> {
            isSeeking = true;
            uiController.highlightCurrentSong();
        });
        progressSlider.setOnMouseReleased(e -> {
            isSeeking = false;
            long time = (long) (progressSlider.getValue() * 1000);
            audioPlayer.controls().setTime(time);
            videoSynchronizer.seek(time);
            updateDiscordPresence(time, currentMediaDuration);
            uiController.highlightCurrentSong();
        });

        double initialVolumePercent = configManager.getVolume() * 100;
        volumeSlider.setValue(initialVolumePercent);
        audioPlayer.audio().setVolume((int) Math.round(initialVolumePercent));
        volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (oldVal.intValue() == newVal.intValue()) {
                return;
            }
            audioPlayer.audio().setVolume(newVal.intValue());
        });
        volumeSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                configManager.setVolume(volumeSlider.getValue() / 100.0);
            }
        });
        volumeSlider.setOnMousePressed(e -> uiController.highlightCurrentSong());
        volumeSlider.setOnMouseReleased(e -> {
            uiController.highlightCurrentSong();
            configManager.setVolume(volumeSlider.getValue() / 100.0);
        });

        playPauseButton.setOnAction(e -> togglePlayPause());
        shuffleButton.setOnAction(e -> toggleShuffle());
        previousButton.setOnAction(e -> uiController.playPreviousFromHistory());
        stopButton.setOnAction(e -> stopPlayback());
        nextButton.setOnAction(e -> uiController.playNextFromHistoryOrNormal());
    }

    public void togglePlayPause() {
        State state = audioPlayer.status().state();
        if (state == State.PLAYING) {
            audioPlayer.controls().pause();
            videoSynchronizer.applyPlayState(false);
            playPauseButton.setText("▶");
            if (discord != null) {
                discord.setIdleStatus();
            }
        } else {
            if (state == State.STOPPED || state == State.ENDED || state == null) {
                 uiController.playSelectedSong();
            } else {
                audioPlayer.controls().play();
                videoSynchronizer.applyPlayState(true);
                playPauseButton.setText("⏸");
                if (discord != null) {
                    long currentTime = audioPlayer.status().time();
                    updateDiscordPresence(currentTime, currentMediaDuration);
                }
            }
        }
    }

    public void stopPlayback() {
        audioPlayer.controls().stop();
        videoSynchronizer.reset();
        playPauseButton.setText("▶");
        onNewMedia();
        uiController.hideVideo();
        uiController.prepareCurrentSongForReplay();
        
        if (discord != null) {
            discord.setIdleStatus();
        }
    }
    
    public void setCurrentSongForDiscord(String songName) {
        this.currentSongNameForDiscord = songName;
    }

    public void updateDiscordPresence(long currentTimeMillis, long totalDurationMillis) {
        if (discord != null && currentSongNameForDiscord != null && !currentSongNameForDiscord.isEmpty()) {
            String artist = "Artista desconocido";
            String title = currentSongNameForDiscord;
            String[] parts = currentSongNameForDiscord.split(" - ", 2);
            if (parts.length == 2) {
                artist = parts[0];
                title = parts[1];
            }
            discord.updateStatus(title, artist, currentTimeMillis, totalDurationMillis);
        }
    }

    public void updatePlayPauseButton(boolean isPlaying) {
        playPauseButton.setText(isPlaying ? "⏸" : "▶");
    }

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        updateShuffleButtonStyle();
    }

    private void updateShuffleButtonStyle() {
        shuffleButton.setStyle(shuffleEnabled ? "-fx-background-color: #00cc00;" : "");
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }
    
    public int getRandomIndex(int bound) {
        if (bound <= 0) return 0;
        return random.nextInt(bound);
    }
    private void updateTimeLabel(double currentSeconds, double totalSeconds) {
        timeLabel.setText(formatTime(currentSeconds) + " / " + formatTime(totalSeconds));
    }
    private String formatTime(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) seconds = 0;
        int s = (int) Math.round(seconds);
        int mins = s / 60;
        int secs = s % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}