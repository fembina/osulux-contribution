package com.osuplayer.lang;

import java.util.Map;

public interface LanguagePack {
    String id();
    String displayName();
    Map<String, String> translations();

    default String translate(String original) {
        if (original == null) {
            return "";
        }
        return translations().getOrDefault(original, original);
    }
}
