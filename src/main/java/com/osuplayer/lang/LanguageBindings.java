package com.osuplayer.lang;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;

public final class LanguageBindings {

    private LanguageBindings() {}

    public static void bindLabeled(Labeled labeled, String spanish) {
        if (labeled == null) {
            return;
        }
        labeled.textProperty().bind(createBinding(spanish));
    }

    public static void bindMenuItem(MenuItem menuItem, String spanish) {
        if (menuItem == null) {
            return;
        }
        menuItem.textProperty().bind(createBinding(spanish));
    }

    public static void bindPrompt(TextInputControl control, String spanish) {
        if (control == null) {
            return;
        }
        control.promptTextProperty().bind(createBinding(spanish));
    }

    public static void bindStageTitle(Stage stage, String spanish) {
        if (stage == null) {
            return;
        }
        stage.titleProperty().bind(createBinding(spanish));
    }

    private static StringBinding createBinding(String spanish) {
        LanguageManager manager = LanguageManager.getInstance();
        return Bindings.createStringBinding(
            () -> manager.translate(spanish),
            manager.languageIdProperty()
        );
    }
}
