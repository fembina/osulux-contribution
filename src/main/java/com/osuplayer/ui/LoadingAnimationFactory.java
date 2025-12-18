package com.osuplayer.ui;

import java.net.URL;

import javafx.scene.Node;
import javafx.scene.CacheHint;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public final class LoadingAnimationFactory {

    private static final String LOADER_GIF_PATH = "/gif/loading.gif";
    private static final Image LOADER_IMAGE = loadLoaderImage();

    private LoadingAnimationFactory() {}

    public static Node createLoader(double size) {
        if (LOADER_IMAGE == null) {
            return createFallback(size);
        }
        return createGifView(Math.max(36d, size));
    }

    private static Node createGifView(double targetSize) {
        
        
        
        Image img = LOADER_IMAGE;
        try {
            URL url = LoadingAnimationFactory.class.getResource(LOADER_GIF_PATH);
            if (url != null) {
                img = new Image(url.toExternalForm(), targetSize, targetSize, true, true);
            }
        } catch (RuntimeException ignored) {
            
        }

        ImageView view = new ImageView(img);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setCache(false);
        view.setCacheHint(CacheHint.QUALITY);
        view.setFitWidth(targetSize);
        view.setFitHeight(targetSize);
        view.setMouseTransparent(true);
        view.setFocusTraversable(false);
        return view;
    }

    private static Node createFallback(double size) {
        ProgressIndicator indicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        indicator.setPrefSize(size, size);
        indicator.setMinSize(size, size);
        indicator.setMaxSize(size, size);
        indicator.setFocusTraversable(false);
        indicator.setMouseTransparent(true);
        return indicator;
    }

    private static Image loadLoaderImage() {
        URL url = LoadingAnimationFactory.class.getResource(LOADER_GIF_PATH);
        if (url == null) {
            return null;
        }
        try {
            return new Image(url.toExternalForm(), true);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
