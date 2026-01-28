package com.osuplayer.dependencies;

public enum RuntimeArchitecture {
    X86,
    X64,
    ARM64;

    public static RuntimeArchitecture current() {
        var arch = System.getProperty("os.arch").toLowerCase();

        if (arch.contains("amd64") || arch.contains("x86_64")) {
            return X64;
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            return ARM64;
        } else if (arch.contains("x86") || arch.contains("i386") || arch.contains("i486") ||
                arch.contains("i586") || arch.contains("i686")) {
            return X86;
        }

        throw new UnsupportedOperationException("Unsupported architecture: " + arch);
    }
}
