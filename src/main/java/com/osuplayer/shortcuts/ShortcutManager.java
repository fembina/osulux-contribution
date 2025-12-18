package com.osuplayer.shortcuts;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;

import com.osuplayer.config.ConfigManager;
import com.osuplayer.lang.I18n;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

public class ShortcutManager {

    private final ConfigManager configManager;
    private final Map<ShortcutAction, KeyCodeCombination> customBindings = new EnumMap<>(ShortcutAction.class);

    public ShortcutManager(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        loadCustomBindings();
    }

    private void loadCustomBindings() {
        for (ShortcutAction action : ShortcutAction.values()) {
            String stored = configManager.getShortcutBinding(action.getId());
            KeyCodeCombination combo = parseCombination(stored);
            if (combo != null) {
                customBindings.put(action, combo);
            }
        }
    }

    public boolean handleKeyEvent(KeyEvent event, Consumer<ShortcutAction> handler) {
        if (event == null || handler == null) {
            return false;
        }
        for (ShortcutAction action : ShortcutAction.values()) {
            KeyCodeCombination custom = customBindings.get(action);
            if (custom != null && custom.match(event)) {
                handler.accept(action);
                return true;
            }
            KeyCodeCombination defaultCombo = action.getDefaultKeyCombination();
            if (defaultCombo != null && defaultCombo.match(event)) {
                handler.accept(action);
                return true;
            }
            for (KeyCodeCombination supplemental : action.getSupplementalKeyCombinations()) {
                if (supplemental.match(event)) {
                    if (isArrowWithoutModifiers(supplemental) && (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown())) {
                        continue;
                    }
                    handler.accept(action);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isArrowWithoutModifiers(KeyCodeCombination combo) {
        if (combo == null) {
            return false;
        }
        KeyCombination.ModifierValue shift = combo.getShift();
        KeyCombination.ModifierValue ctrl = combo.getControl();
        KeyCombination.ModifierValue alt = combo.getAlt();
        KeyCombination.ModifierValue meta = combo.getMeta();
        boolean noModifiers = shift == KeyCombination.ModifierValue.ANY
            && ctrl == KeyCombination.ModifierValue.ANY
            && alt == KeyCombination.ModifierValue.ANY
            && meta == KeyCombination.ModifierValue.ANY;
        return noModifiers && (combo.getCode() == KeyCode.UP || combo.getCode() == KeyCode.DOWN || combo.getCode() == KeyCode.LEFT || combo.getCode() == KeyCode.RIGHT);
    }

    public KeyCodeCombination getEffectiveCombination(ShortcutAction action) {
        KeyCodeCombination combination = customBindings.get(action);
        if (combination != null) {
            return combination;
        }
        return action.getDefaultKeyCombination();
    }

    public String getDisplayText(ShortcutAction action) {
        KeyCombination combo = getEffectiveCombination(action);
        return combo == null ? "Sin definir" : formatForDisplay(combo);
    }

    public void setCustomCombination(ShortcutAction action, KeyCodeCombination combination) {
        if (action == null || !action.isCustomizable()) {
            return;
        }
        if (combination == null) {
            clearCustomCombination(action);
            return;
        }
        customBindings.put(action, combination);
        configManager.setShortcutBinding(action.getId(), toConfigString(combination));
    }

    public void clearCustomCombination(ShortcutAction action) {
        if (action == null || !action.isCustomizable()) {
            return;
        }
        customBindings.remove(action);
        configManager.clearShortcutBinding(action.getId());
    }

    public boolean hasCustomCombination(ShortcutAction action) {
        return action != null && action.isCustomizable() && customBindings.containsKey(action);
    }

    public boolean isCombinationInUse(KeyCodeCombination candidate, ShortcutAction excluded) {
        if (candidate == null) {
            return false;
        }
        for (ShortcutAction action : ShortcutAction.values()) {
            if (action == excluded) {
                continue;
            }
            KeyCodeCombination custom = customBindings.get(action);
            if (custom != null) {
                if (custom.equals(candidate)) {
                    return true;
                }
            } else {
                KeyCodeCombination defaultCombo = action.getDefaultKeyCombination();
                if (defaultCombo != null && defaultCombo.equals(candidate)) {
                    return true;
                }
            }
            for (KeyCodeCombination supplemental : action.getSupplementalKeyCombinations()) {
                if (supplemental.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<ShortcutAction> findActionForCombination(KeyCodeCombination candidate, ShortcutAction excluded) {
        if (candidate == null) {
            return Optional.empty();
        }
        for (ShortcutAction action : ShortcutAction.values()) {
            if (action == excluded) {
                continue;
            }
            KeyCodeCombination custom = customBindings.get(action);
            if (custom != null) {
                if (custom.equals(candidate)) {
                    return Optional.of(action);
                }
            } else {
                KeyCodeCombination defaultCombo = action.getDefaultKeyCombination();
                if (defaultCombo != null && defaultCombo.equals(candidate)) {
                    return Optional.of(action);
                }
            }
            for (KeyCodeCombination supplemental : action.getSupplementalKeyCombinations()) {
                if (supplemental.equals(candidate)) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    public KeyCodeCombination buildCombinationFromEvent(KeyEvent event) {
        if (event == null) {
            return null;
        }
        KeyCode code = event.getCode();
        if (code == null || code.isModifierKey()) {
            return null;
        }
        return new KeyCodeCombination(
            code,
            event.isShiftDown() ? KeyCombination.SHIFT_DOWN : KeyCombination.SHIFT_ANY,
            event.isControlDown() ? KeyCombination.CONTROL_DOWN : KeyCombination.CONTROL_ANY,
            event.isAltDown() ? KeyCombination.ALT_DOWN : KeyCombination.ALT_ANY,
            event.isMetaDown() ? KeyCombination.META_DOWN : KeyCombination.META_ANY
        );
    }

    public static KeyCodeCombination parseCombination(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] tokens = raw.trim().toUpperCase(Locale.ROOT).split("\\+");
        boolean shift = false;
        boolean ctrl = false;
        boolean alt = false;
        boolean meta = false;
        String keyToken = null;
        for (String token : tokens) {
            String trimmed = token.trim();
            switch (trimmed) {
                case "SHIFT" -> shift = true;
                case "CTRL", "CONTROL" -> ctrl = true;
                case "ALT", "OPTION" -> alt = true;
                case "META", "COMMAND", "CMD" -> meta = true;
                default -> keyToken = trimmed;
            }
        }
        if (keyToken == null || keyToken.isBlank()) {
            return null;
        }
        KeyCode code;
        try {
            code = KeyCode.valueOf(keyToken);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return new KeyCodeCombination(
            code,
            shift ? KeyCombination.SHIFT_DOWN : KeyCombination.SHIFT_ANY,
            ctrl ? KeyCombination.CONTROL_DOWN : KeyCombination.CONTROL_ANY,
            alt ? KeyCombination.ALT_DOWN : KeyCombination.ALT_ANY,
            meta ? KeyCombination.META_DOWN : KeyCombination.META_ANY
        );
    }

    private String toConfigString(KeyCodeCombination combination) {
        if (combination == null) {
            return null;
        }
        StringJoiner joiner = new StringJoiner("+");
        if (combination.getShift() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("SHIFT");
        }
        if (combination.getControl() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("CTRL");
        }
        if (combination.getAlt() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("ALT");
        }
        if (combination.getMeta() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("META");
        }
        joiner.add(combination.getCode().name());
        return joiner.toString();
    }

    public String formatForDisplay(KeyCombination combination) {
        if (!(combination instanceof KeyCodeCombination keyCombo)) {
            return combination == null ? "" : combination.getName();
        }
        StringJoiner joiner = new StringJoiner(" + ");
        if (keyCombo.getShift() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("Shift");
        }
        if (keyCombo.getControl() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("Ctrl");
        }
        if (keyCombo.getAlt() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("Alt");
        }
        if (keyCombo.getMeta() == KeyCombination.ModifierValue.DOWN) {
            joiner.add("Meta");
        }
        joiner.add(readableKeyName(keyCombo.getCode()));
        return joiner.toString();
    }

    private String readableKeyName(KeyCode code) {
        if (code == null) {
            return "";
        }
        String key;
        
        switch (code) {
            case LEFT -> key = "Flecha izquierda";
            case RIGHT -> key = "Flecha derecha";
            case UP -> key = "Flecha arriba";
            case DOWN -> key = "Flecha abajo";
            case SPACE -> key = "Espacio";
            case DELETE -> key = "Supr";
            case BACK_SPACE -> key = "Retroceso";
            case VOLUME_DOWN -> key = "Flecha abajo";
            case VOLUME_UP -> key = "Flecha arriba";
            default -> {
                String raw = code.getName();
                if (raw == null || raw.isBlank()) {
                    return code.name();
                }
                return raw;
            }
        }
        return I18n.tr(key);
    }
}
