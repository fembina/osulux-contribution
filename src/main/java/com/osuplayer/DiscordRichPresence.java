package com.osuplayer;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.activity.ActivityType;

public class DiscordRichPresence {

    private static final Logger LOGGER = Logger.getLogger(DiscordRichPresence.class.getName());

    private Core core;
    private final Thread callbackThread;
    private volatile boolean running = false;
    private final Random random = new Random();
    
    private static final List<String> COVER_ASSETS = Arrays.asList(
        "cover1",
        "cover2",
        "cover3",
        "cover4"
    );

    public DiscordRichPresence() {
        this.callbackThread = new Thread(() -> {
            while (running) {
                try {
                    if (core != null) {
                        core.runCallbacks();
                    }
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "Discord-Callback-Thread");
    }

    public void start(long clientId) {
        try {
            Core.init(new File("lib/discord_game_sdk.dll"));
            try (CreateParams params = new CreateParams()) {
                params.setClientID(clientId);
                params.setFlags(CreateParams.getDefaultFlags());
                this.core = new Core(params);
                this.running = true;
                this.callbackThread.start();
                setIdleStatus();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "No se pudo inicializar Discord SDK", e);
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        try {
            callbackThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrumpido mientras se esperaba al hilo de Discord", e);
        }
        
        if (core != null) {
            try {
                core.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "No se pudo cerrar Discord SDK limpiamente", e);
            }
        }
    }

    public void updateStatus(String songTitle, String artist, long currentTimeMillis, long totalDurationMillis) {
        if (core == null || !running) return;
        try (Activity activity = new Activity()) {
            activity.setType(ActivityType.LISTENING);
            activity.setDetails(songTitle);
            activity.setState("por " + artist);
            activity.assets().setLargeText("Escuchando en Osulux");

            if (!COVER_ASSETS.isEmpty()) {
                int randomIndex = random.nextInt(COVER_ASSETS.size());
                String randomAssetKey = COVER_ASSETS.get(randomIndex);
                activity.assets().setLargeImage(randomAssetKey);
            } else {
                activity.assets().setLargeImage("osulux-logo");
            }
            
            if (totalDurationMillis > 0) {
                Instant now = Instant.now();
                activity.timestamps().setStart(now.minusMillis(currentTimeMillis));
                activity.timestamps().setEnd(now.plusMillis(totalDurationMillis - currentTimeMillis));
            }
            
            core.activityManager().updateActivity(activity);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error al actualizar la actividad de Discord", e);
        }
    }

    public void setIdleStatus() {
        if (core == null || !running) return;
        try (Activity activity = new Activity()) {
            activity.setType(ActivityType.LISTENING);
            activity.setDetails("Navegando por la música");
            activity.setState("en Osulux");
            activity.assets().setLargeImage("osulux-logo");
            activity.assets().setLargeText("Osulux Music Player");
            core.activityManager().updateActivity(activity);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error al actualizar el estado inactivo de Discord", e);
        }
    }
}