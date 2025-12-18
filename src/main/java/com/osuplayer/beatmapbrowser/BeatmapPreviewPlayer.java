package com.osuplayer.beatmapbrowser;

import java.util.Objects;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaPlayer.Status;

public class BeatmapPreviewPlayer {

    public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private static final String PREVIEW_BASE_URL = "https://b.ppy.sh/preview/";

    private MediaPlayer mediaPlayer;
    private long currentBeatmapsetId = -1;
    private double volume = 0.7d;
    private Consumer<State> stateListener;
    private State currentState = State.IDLE;

    public BeatmapPreviewPlayer(Consumer<State> stateListener) {
        this.stateListener = stateListener;
        notifyState(State.IDLE);
    }

    public void setVolume(double volume) {
        this.volume = clamp(volume);
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(this.volume);
        }
    }

    public double getVolume() {
        return volume;
    }

    public void setStateListener(Consumer<State> stateListener) {
        this.stateListener = stateListener;
        notifyState(currentState);
    }

    public long getCurrentBeatmapsetId() {
        return currentBeatmapsetId;
    }

    public State getCurrentState() {
        return currentState;
    }

    public void toggle(long beatmapsetId) {
        if (beatmapsetId <= 0) {
            return;
        }
        if (mediaPlayer != null && beatmapsetId == currentBeatmapsetId) {
            toggleExistingPlayer();
            return;
        }
        startNewPreview(beatmapsetId);
    }

    public void stop() {
        stopInternal(State.IDLE);
    }

    public void dispose() {
        stopInternal(State.IDLE);
        stateListener = null;
    }

    private void toggleExistingPlayer() {
        Status status = mediaPlayer.getStatus();
        switch (status) {
            case PLAYING -> {
                mediaPlayer.pause();
                notifyState(State.PAUSED);
            }
            case PAUSED, STOPPED, READY -> {
                mediaPlayer.play();
                notifyState(State.PLAYING);
            }
            case UNKNOWN, HALTED -> notifyState(State.ERROR);
            default -> { }
        }
    }

    private void startNewPreview(long beatmapsetId) {
        stopInternal(State.IDLE);
        currentBeatmapsetId = beatmapsetId;
        notifyState(State.LOADING);
        Media media;
        try {
            media = new Media(buildPreviewUrl(beatmapsetId));
        } catch (MediaException ex) {
            currentBeatmapsetId = -1;
            notifyState(State.ERROR);
            return;
        }

        mediaPlayer = new MediaPlayer(media);
        mediaPlayer.setVolume(volume);
        mediaPlayer.setOnReady(() -> {
            mediaPlayer.play();
            notifyState(State.PLAYING);
        });
        mediaPlayer.setOnPlaying(() -> notifyState(State.PLAYING));
        mediaPlayer.setOnPaused(() -> notifyState(State.PAUSED));
        mediaPlayer.setOnEndOfMedia(() -> {
            mediaPlayer.stop();
            notifyState(State.PAUSED);
        });
        mediaPlayer.setOnStopped(() -> notifyState(State.IDLE));
        mediaPlayer.setOnError(() -> notifyState(State.ERROR));
    }

    private void stopInternal(State terminalState) {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        currentBeatmapsetId = -1;
        notifyState(terminalState);
    }

    private void notifyState(State newState) {
        currentState = Objects.requireNonNull(newState);
        Consumer<State> listener = stateListener;
        if (listener == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            listener.accept(newState);
        } else {
            Platform.runLater(() -> listener.accept(newState));
        }
    }

    private static double clamp(double value) {
        if (value < 0d) return 0d;
        if (value > 1d) return 1d;
        return value;
    }

    private static String buildPreviewUrl(long beatmapsetId) {
        return PREVIEW_BASE_URL + beatmapsetId + ".mp3";
    }
}
