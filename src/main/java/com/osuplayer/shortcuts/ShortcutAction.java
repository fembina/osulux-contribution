package com.osuplayer.shortcuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.osuplayer.lang.I18n;

import javafx.scene.input.KeyCodeCombination;

public enum ShortcutAction {
    PREVIOUS_TRACK(
        "previous-track",
        "Canción anterior",
        "",
        "CTRL+LEFT",
        List.of("FN + F5"),
        List.of("MEDIA_PREVIOUS", "F5"),
        true
    ),
    NEXT_TRACK(
        "next-track",
        "Siguiente canción",
        "",
        "CTRL+RIGHT",
        List.of("FN + F6"),
        List.of("MEDIA_NEXT", "F6"),
        true
    ),
    SEEK_BACK_SHORT(
        "seek-back-short",
        "Retroceder 10s",
        "",
        "LEFT",
        List.of("Flecha izquierda"),
        List.of(),
        true
    ),
    SEEK_FORWARD_SHORT(
        "seek-forward-short",
        "Avanzar 10s",
        "",
        "RIGHT",
        List.of("Flecha derecha"),
        List.of(),
        true
    ),
    SEEK_BACK_LONG(
        "seek-back-long",
        "Retroceder 30s",
        "",
        "ALT+LEFT",
        List.of("Alt + Flecha izquierda"),
        List.of(),
        true
    ),
    SEEK_FORWARD_LONG(
        "seek-forward-long",
        "Avanzar 30s",
        "",
        "ALT+RIGHT",
        List.of("Alt + Flecha derecha"),
        List.of(),
        true
    ),
    PLAY_PAUSE(
        "play-pause",
        "Reproducir / Pausar",
        "",
        "SPACE",
        List.of("FN + F7"),
        List.of("F7"),
        true
    ),
    STOP(
        "stop",
        "Detener",
        "",
        "BACK_SPACE",
        List.of("FN + F8"),
        List.of("F8"),
        true
    ),
    VOLUME_MUTE(
        "volume-mute",
        "Silenciar",
        "",
        "M",
        List.of("FN + F9"),
        List.of("VOLUME_MUTE", "F9"),
        true
    ),
    VOLUME_DOWN(
        "volume-down",
        "Bajar volumen",
        "",
        "VOLUME_DOWN",
        List.of("FN + F10"),
        List.of("DOWN", "F10"),
        true
    ),
    VOLUME_UP(
        "volume-up",
        "Subir volumen",
        "",
        "VOLUME_UP",
        List.of("FN + F11"),
        List.of("UP", "F11"),
        true
    ),
    TEMPO_DOWN(
        "tempo-down",
        "Bajar velocidad",
        "",
        "CTRL+DOWN",
        List.of("Ctrl + Flecha abajo"),
        List.of(),
        true
    ),
    TEMPO_UP(
        "tempo-up",
        "Subir velocidad",
        "",
        "CTRL+UP",
        List.of("Ctrl + Flecha arriba"),
        List.of(),
        true
    ),
    SHUFFLE_TOGGLE(
        "shuffle-toggle",
        "Alternar aleatorio",
        "",
        "S",
        List.of("S"),
        List.of(),
        true
    ),
    DELETE_SONG(
        "delete-song",
        "Eliminar canción",
        "",
        "DELETE",
        List.of("Supr"),
        List.of(),
        true
    );

    private final String id;
    private final String displayName;
    private final String description;
    private final String defaultCombination;
    private final List<String> fixedShortcutDescriptions;
    private final List<String> supplementalCombinationStrings;
    private final boolean customizable;

    private KeyCodeCombination cachedDefaultCombination;
    private List<KeyCodeCombination> cachedSupplementalCombinations;

    ShortcutAction(String id,
                   String displayName,
                   String description,
                   String defaultCombination,
                   List<String> fixedShortcutDescriptions,
                   List<String> supplementalCombinationStrings,
                   boolean customizable) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.defaultCombination = defaultCombination;
        this.fixedShortcutDescriptions = fixedShortcutDescriptions;
        this.supplementalCombinationStrings = supplementalCombinationStrings;
        this.customizable = customizable;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return I18n.tr(displayName);
    }

    public String getDescription() {
        return I18n.tr(description);
    }

    public String getDefaultCombination() {
        return defaultCombination;
    }

    public List<String> getFixedShortcutDescriptions() {
        if (fixedShortcutDescriptions == null || fixedShortcutDescriptions.isEmpty()) {
            return fixedShortcutDescriptions;
        }
        return fixedShortcutDescriptions.stream()
            .map(I18n::tr)
            .collect(Collectors.toList());
    }

    public List<String> getSupplementalCombinationStrings() {
        if (supplementalCombinationStrings == null || supplementalCombinationStrings.isEmpty()) {
            return supplementalCombinationStrings;
        }
        return supplementalCombinationStrings.stream()
            .map(I18n::tr)
            .collect(Collectors.toList());
    }

    public boolean isCustomizable() {
        return customizable;
    }

    public KeyCodeCombination getDefaultKeyCombination() {
        if (defaultCombination == null || defaultCombination.isBlank()) {
            return null;
        }
        if (cachedDefaultCombination == null) {
            cachedDefaultCombination = ShortcutManager.parseCombination(defaultCombination);
        }
        return cachedDefaultCombination;
    }

    public List<KeyCodeCombination> getSupplementalKeyCombinations() {
        if (cachedSupplementalCombinations == null) {
            if (supplementalCombinationStrings == null || supplementalCombinationStrings.isEmpty()) {
                cachedSupplementalCombinations = Collections.emptyList();
            } else {
                List<KeyCodeCombination> combos = new ArrayList<>();
                for (String combo : supplementalCombinationStrings) {
                    KeyCodeCombination parsed = ShortcutManager.parseCombination(combo);
                    if (parsed != null) {
                        combos.add(parsed);
                    }
                }
                cachedSupplementalCombinations = Collections.unmodifiableList(combos);
            }
        }
        return cachedSupplementalCombinations;
    }
}
