package com.osuplayer.lang;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

public final class LanguageManager {

    private static final LanguageManager INSTANCE = new LanguageManager();

    private final Map<String, LanguagePack> languagePacks = new LinkedHashMap<>();
    private final ReadOnlyStringWrapper languageIdProperty = new ReadOnlyStringWrapper("es");
    private LanguagePack current;

    private LanguageManager() {
        register(new Espa\u00f1ol());
        register(new English());
        current = languagePacks.get(languageIdProperty.get());
    }

    public static LanguageManager getInstance() {
        return INSTANCE;
    }

    private void register(LanguagePack pack) {
        languagePacks.put(pack.id(), pack);
    }

    public Collection<LanguagePack> getAvailableLanguages() {
        return languagePacks.values();
    }

    public LanguagePack getCurrent() {
        return current;
    }

    public String translate(String original) {
        if (current == null) {
            return original;
        }
        if (Objects.equals(current.id(), "es")) {
            return original == null ? "" : original;
        }
        return current.translate(original);
    }

    public void setLanguage(String languageId) {
        LanguagePack pack = languagePacks.get(languageId);
        if (pack == null) {
            pack = languagePacks.get("es");
        }
        if (pack == null || Objects.equals(languageIdProperty.get(), pack.id())) {
            current = pack;
            return;
        }
        current = pack;
        languageIdProperty.set(pack.id());
    }

    public String getLanguageId() {
        return languageIdProperty.get();
    }

    public ReadOnlyStringProperty languageIdProperty() {
        return languageIdProperty.getReadOnlyProperty();
    }
}
