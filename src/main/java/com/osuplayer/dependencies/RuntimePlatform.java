package com.osuplayer.dependencies;

public enum RuntimePlatform {
    WINDOWS,
    LINUX,
    OSX;

    public static RuntimePlatform current() {
        var platform = System.getProperty("os.name").toLowerCase();

        if (platform.contains("win")) {
            return WINDOWS;
        } else if (platform.contains("mac")) {
            return OSX;
        } else if (platform.contains("nix") || platform.contains("nux") || platform.contains("aix")) {
            return LINUX;
        }

        throw new UnsupportedOperationException("Unsupported operating system: " + platform);
    }
}
