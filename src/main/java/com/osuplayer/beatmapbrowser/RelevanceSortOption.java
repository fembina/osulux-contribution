package com.osuplayer.beatmapbrowser;

import com.osuplayer.lang.I18n;

public enum RelevanceSortOption {
    MOST_RELEVANT("Más relevantes"),
    LEAST_RELEVANT("Menos relevantes"),
    MOST_DOWNLOADED("Más descargados"),
    LEAST_DOWNLOADED("Menos descargados"),
    MOST_FAVOURITED("Más favoritos"),
    LEAST_FAVOURITED("Menos favoritos"),
    MOST_RECENT("Más recientes"),
    OLDEST("Más antiguos");

    private final String translationKey;

    RelevanceSortOption(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    @Override
    public String toString() {
        return I18n.tr(translationKey);
    }
}
