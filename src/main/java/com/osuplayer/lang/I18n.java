package com.osuplayer.lang;

import java.util.Locale;

public final class I18n {

    private I18n() {}

    public static String tr(String spanish) {
        return LanguageManager.getInstance().translate(spanish);
    }

    public static String trf(String spanishFormat, Object... args) {
        String template = tr(spanishFormat);
        return args == null || args.length == 0
            ? template
            : String.format(Locale.ROOT, template, args);
    }
}
