package com.osuplayer.beatmapbrowser;

import com.osuplayer.lang.I18n;

public enum VideoFilterOption {
    ALL("Todos"),
    ONLY_WITH_VIDEO("Solo con video"),
    ONLY_WITHOUT_VIDEO("Solo sin video");

    private final String translationKey;

    VideoFilterOption(String translationKey) {
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
