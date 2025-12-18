package com.osuplayer.shortcuts;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import javafx.application.Platform;


public final class GlobalMediaKeyService implements NativeKeyListener {

    private final java.util.function.Consumer<ShortcutAction> actionHandler;
    private boolean started;

    public GlobalMediaKeyService(java.util.function.Consumer<ShortcutAction> actionHandler) {
        this.actionHandler = Objects.requireNonNull(actionHandler, "actionHandler");
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        silenceNativeHookLogs();
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            started = true;
        } catch (NativeHookException | UnsatisfiedLinkError | RuntimeException ex) {
            String targetDir = System.getProperty("jnativehook.lib.location", System.getProperty("java.io.tmpdir"));
            boolean retried = false;
            if (ex instanceof UnsatisfiedLinkError && ex.getMessage() != null && ex.getMessage().contains("Unable to extract")) {
                retried = tryManualExtractAndLoad(targetDir);
                if (retried) {
                    try {
                        GlobalScreen.registerNativeHook();
                        GlobalScreen.addNativeKeyListener(this);
                        started = true;
                        return; 
                    } catch (NativeHookException | UnsatisfiedLinkError | RuntimeException retryEx) {
                        logHookFailure(retryEx, targetDir, retried);
                        started = false;
                        return;
                    }
                }
            }
            logHookFailure(ex, targetDir, retried);
            started = false;
        }
    }

    public synchronized void close() {
        if (!started) {
            return;
        }
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException ex) {
            System.err.println("No se pudo desregistrar el hook nativo: " + ex.getMessage());
        } finally {
            started = false;
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent nativeEvent) {
        if (nativeEvent == null) {
            return;
        }
        ShortcutAction action = mapToAction(nativeEvent);
        if (action != null) {
            
            Platform.runLater(() -> actionHandler.accept(action));
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent nativeEvent) {
        
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent nativeEvent) {
        
    }

    private ShortcutAction mapToAction(NativeKeyEvent event) {
        
        return switch (event.getKeyCode()) {
            case NativeKeyEvent.VC_MEDIA_PREVIOUS, NativeKeyEvent.VC_F5 -> ShortcutAction.PREVIOUS_TRACK;
            case NativeKeyEvent.VC_MEDIA_NEXT, NativeKeyEvent.VC_F6 -> ShortcutAction.NEXT_TRACK;
            case NativeKeyEvent.VC_MEDIA_PLAY, NativeKeyEvent.VC_F7 -> ShortcutAction.PLAY_PAUSE;
            case NativeKeyEvent.VC_MEDIA_STOP, NativeKeyEvent.VC_F8 -> ShortcutAction.STOP;
            case NativeKeyEvent.VC_VOLUME_MUTE, NativeKeyEvent.VC_F9 -> ShortcutAction.VOLUME_MUTE;
            case NativeKeyEvent.VC_VOLUME_DOWN, NativeKeyEvent.VC_F10 -> ShortcutAction.VOLUME_DOWN;
            case NativeKeyEvent.VC_VOLUME_UP, NativeKeyEvent.VC_F11 -> ShortcutAction.VOLUME_UP;
            default -> null;
        };
    }

    private void silenceNativeHookLogs() {
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.SEVERE);
    }

    private void logHookFailure(Throwable ex, String targetDir, boolean retried) {
        System.err.println("No se pudieron registrar las teclas multimedia globales (se desactiva el hook): "
            + ex.getMessage() + " | ruta: " + targetDir + " | reintento manual: " + retried);
    }

    private boolean tryManualExtractAndLoad(String dir) {
        String resourcePath = "/com/github/kwhat/jnativehook/lib/windows/x86_64/JNativeHook.dll";
        Path targetDir = dir == null || dir.isBlank()
            ? Path.of(System.getProperty("java.io.tmpdir"), "jnativehook-cache")
            : Path.of(dir);
        try {
            Files.createDirectories(targetDir);
        } catch (IOException ioEx) {
            System.err.println("No se pudo crear el directorio para JNativeHook: " + ioEx.getMessage());
            return false;
        }

        Path dllPath = targetDir.resolve("JNativeHook.dll");
        if (Files.exists(dllPath)) {
            try {
                System.load(dllPath.toString());
                System.setProperty("jnativehook.lib.location", targetDir.toString());
                return true;
            } catch (UnsatisfiedLinkError linkEx) {
                System.err.println("Fallo al cargar JNativeHook existente: " + linkEx.getMessage());
            }
        }
        try (InputStream in = GlobalMediaKeyService.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("No se encontró el recurso nativo de JNativeHook en " + resourcePath);
                return false;
            }
            Files.copy(in, dllPath, StandardCopyOption.REPLACE_EXISTING);
            System.load(dllPath.toString());
            
            System.setProperty("jnativehook.lib.location", targetDir.toString());
            return true;
        } catch (IOException | UnsatisfiedLinkError loadEx) {
            System.err.println("Fallo al extraer/cargar JNativeHook manualmente: " + loadEx.getMessage());
            return false;
        }
    }
}
