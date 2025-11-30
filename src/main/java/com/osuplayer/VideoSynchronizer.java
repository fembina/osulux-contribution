package com.osuplayer;

import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;


public class VideoSynchronizer {

    private static final long DRIFT_TOLERANCE_MS = 80L;
    private static final long RESYNC_INTERVAL_MS = 400L;
    private static final long RESYNC_GRACE_PERIOD_MS = 2000L;
    private static final boolean AUTO_RESYNC_ENABLED = Boolean.parseBoolean(System.getProperty("osulux.video.resync", "false"));

    private final EmbeddedMediaPlayer audioPlayer;
    private final EmbeddedMediaPlayer videoPlayer;

    private long videoOffsetMillis;
    private boolean hasVideo;
    private boolean waitingForOffset;
    private boolean videoStarted;
    private boolean videoVisible;
    private String currentVideoPath;
    private long pendingSeekMillis = -1;
    private long lastResyncAttemptMillis = 0;
    private long videoStartEpochMillis = 0;
    private Runnable onVideoReady;
    private Runnable onVideoReset;

    public VideoSynchronizer(EmbeddedMediaPlayer audioPlayer, EmbeddedMediaPlayer videoPlayer) {
        this.audioPlayer = audioPlayer;
        this.videoPlayer = videoPlayer;

        this.videoPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                handleVideoPlaying();
            }

            @Override
            public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
                flushPendingSeek();
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                handleVideoError();
            }
        });
    }

    public void setCallbacks(Runnable onVideoReady, Runnable onVideoReset) {
        this.onVideoReady = onVideoReady;
        this.onVideoReset = onVideoReset;
    }

    public void reset() {
        stopCurrentVideo();
        notifyVideoReset();
    }

    public void loadVideo(String videoPath, long offsetMillis) {
        stopCurrentVideo();
        notifyVideoReset();

        if (videoPath == null || videoPath.isBlank()) {
            return;
        }

        this.currentVideoPath = videoPath;
        this.videoOffsetMillis = offsetMillis;
        this.hasVideo = true;
        this.waitingForOffset = offsetMillis > 0;
        this.videoStarted = false;
        this.videoVisible = false;
        this.pendingSeekMillis = offsetMillis < 0 ? Math.abs(offsetMillis) : -1;
        this.lastResyncAttemptMillis = 0;

        if (!waitingForOffset) {
            startVideo(Math.max(0, pendingSeekMillis));
        }
    }

    public void onAudioTimeChanged(long audioTimeMillis) {
        if (!hasVideo) return;

        if (waitingForOffset) {
            if (audioTimeMillis < videoOffsetMillis) {
                return;
            }

            waitingForOffset = false;
            ensureVideoStarted(audioTimeMillis - videoOffsetMillis);
            applyPlayState(isAudioPlaying());
            return;
        }

        if (AUTO_RESYNC_ENABLED) {
            maybeResyncWithAudio(audioTimeMillis);
        }
    }

    public void seek(long audioTargetMillis) {
        if (!hasVideo) return;

        long targetVideoTime = audioTargetMillis - videoOffsetMillis;
        if (targetVideoTime < 0) {
            waitingForOffset = true;
            pendingSeekMillis = 0;
            stopVideoPlayback();
        } else {
            waitingForOffset = false;
            ensureVideoStarted(targetVideoTime);
            if (!isAudioPlaying()) {
                pauseVideo();
            } else {
                playVideo();
            }
        }
    }

    public void applyPlayState(boolean audioPlaying) {
        if (!hasVideo) return;
        if (!audioPlaying) {
            pauseVideo();
            return;
        }

        if (waitingForOffset) {
            pauseVideo();
        } else {
            playVideo();
        }
    }

    private boolean isAudioPlaying() {
        State state = audioPlayer.status().state();
        return state == State.PLAYING;
    }

    private void playVideo() {
        videoPlayer.submit(() -> {
            State state = videoPlayer.status().state();
            if (state == State.PLAYING) {
                videoPlayer.controls().setPause(false);
            } else {
                videoPlayer.controls().play();
            }
        });
    }

    private void pauseVideo() {
        videoPlayer.submit(() -> {
            State state = videoPlayer.status().state();
            if (state == State.PLAYING || state == State.PAUSED) {
                videoPlayer.controls().setPause(true);
            }
        });
    }

    private void seekVideo(long targetMillis) {
        if (targetMillis < 0) targetMillis = 0;
        pendingSeekMillis = targetMillis;
        flushPendingSeek();
    }

    private void handleVideoPlaying() {
        if (!hasVideo) return;
        flushPendingSeek();
        if (waitingForOffset) {
            pendingSeekMillis = 0;
            flushPendingSeek();
            pauseVideo();
        } else {
            notifyVideoReady();
        }
    }

    private void notifyVideoReady() {
        if (videoVisible) return;
        videoVisible = true;
        if (onVideoReady != null) {
            onVideoReady.run();
        }
    }

    private void notifyVideoReset() {
        videoVisible = false;
        if (onVideoReset != null) {
            onVideoReset.run();
        }
    }

    private void flushPendingSeek() {
        if (!hasVideo) return;
        final long targetMillis = pendingSeekMillis;
        if (targetMillis < 0) return;
        videoPlayer.submit(() -> {
            State state = videoPlayer.status().state();
            if (state != State.PLAYING && state != State.PAUSED) {
                return;
            }
            if (pendingSeekMillis != targetMillis) {
                return;
            }
            videoPlayer.controls().setTime(targetMillis);
            pendingSeekMillis = -1;
        });
    }

    private void handleVideoError() {
        stopCurrentVideo();
        notifyVideoReset();
    }

    private void maybeResyncWithAudio(long audioTimeMillis) {
        if (!videoStarted) return;

        long sinceStart = System.currentTimeMillis() - videoStartEpochMillis;
        if (sinceStart < RESYNC_GRACE_PERIOD_MS) {
            return;
        }

        long videoTime = videoPlayer.status().time();
        if (videoTime < 0) return;

        long desiredVideoTime = Math.max(0, audioTimeMillis - videoOffsetMillis);
        long drift = Math.abs(videoTime - desiredVideoTime);
        if (drift <= DRIFT_TOLERANCE_MS) return;

        long now = System.currentTimeMillis();
        if (now - lastResyncAttemptMillis < RESYNC_INTERVAL_MS) return;

        lastResyncAttemptMillis = now;
        seekVideo(desiredVideoTime);
    }

    private void ensureVideoStarted(long startMillis) {
        if (!hasVideo || currentVideoPath == null) return;
        long clamped = Math.max(0, startMillis);
        if (!videoStarted) {
            startVideo(clamped);
        } else {
            seekVideo(clamped);
        }
        notifyVideoReady();
    }

    private void startVideo(long startMillis) {
        if (currentVideoPath == null) return;
        videoStarted = true;
        pendingSeekMillis = startMillis;
        videoStartEpochMillis = System.currentTimeMillis();
        videoPlayer.media().play(currentVideoPath, ":no-audio");
        if (!waitingForOffset) {
            notifyVideoReady();
        }
    }

    private void stopVideoPlayback() {
        if (!videoStarted) return;
        videoPlayer.controls().stop();
        videoStarted = false;
        pendingSeekMillis = -1;
    }

    private void stopCurrentVideo() {
        hasVideo = false;
        waitingForOffset = false;
        videoOffsetMillis = 0;
        pendingSeekMillis = -1;
        lastResyncAttemptMillis = 0;
        videoStarted = false;
        videoStartEpochMillis = 0;
        videoVisible = false;
        currentVideoPath = null;
        videoPlayer.controls().stop();
    }
}
