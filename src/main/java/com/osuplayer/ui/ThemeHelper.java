package com.osuplayer.ui;

import java.net.URL;

import javafx.collections.ObservableList;
import javafx.scene.Scene;

public final class ThemeHelper {

    private ThemeHelper() {}

    public static boolean applyTheme(Scene scene, String themeId) {
        if (scene == null) {
            return false;
        }
        return applyTheme(scene.getStylesheets(), themeId);
    }

    public static boolean applyTheme(ObservableList<String> stylesheets, String themeId) {
        if (stylesheets == null) {
            return false;
        }
        stylesheets.removeIf(s -> s.contains("theme-"));
        String css = resolveTheme(themeId);
        if (css == null) {
            return false;
        }
        if (!stylesheets.contains(css)) {
            stylesheets.add(css);
        }
        return true;
    }

    private static String resolveTheme(String themeId) {
        if (themeId == null || themeId.isBlank()) {
            return null;
        }
        URL url = ThemeHelper.class.getResource("/themes/theme-" + themeId + ".css");
        return url == null ? null : url.toExternalForm();
    }

}
