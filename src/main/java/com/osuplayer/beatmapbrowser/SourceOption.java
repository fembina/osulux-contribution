package com.osuplayer.beatmapbrowser;

import com.osuplayer.lang.I18n;
import com.osuplayer.mirrors.MirrorServer;

public final class SourceOption {
    private final String label;
    private final boolean official;
    private final boolean autoMirrors;
    private final MirrorServer server;
    private final boolean translatable;

    public SourceOption(String label, boolean official, boolean autoMirrors, MirrorServer server) {
        this(label, official, autoMirrors, server, true);
    }

    public SourceOption(String label, boolean official, boolean autoMirrors, MirrorServer server, boolean translatable) {
        this.label = label;
        this.official = official;
        this.autoMirrors = autoMirrors;
        this.server = server;
        this.translatable = translatable;
    }

    public String label() {
        return label;
    }

    public boolean official() {
        return official;
    }

    public boolean autoMirrors() {
        return autoMirrors;
    }

    public MirrorServer server() {
        return server;
    }

    @Override
    public String toString() {
        return translatable ? I18n.tr(label) : label;
    }
}
