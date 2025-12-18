package com.osuplayer.util;

import java.io.InputStream;
import java.net.URL;

import javafx.scene.image.Image;


public final class IconResources {

    private static final String[] CANDIDATE_PATHS = {"/jpg/Icon.jpg", "/Icon.jpg"};

    private IconResources() {}

    
    public static InputStream openStream() {
        for (String path : CANDIDATE_PATHS) {
            InputStream stream = IconResources.class.getResourceAsStream(path);
            if (stream != null) {
                return stream;
            }
        }
        return null;
    }

    
    public static URL locate() {
        for (String path : CANDIDATE_PATHS) {
            URL url = IconResources.class.getResource(path);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    
    public static Image loadImage() {
        URL url = locate();
        return url == null ? null : new Image(url.toExternalForm());
    }
}
