package com.osuplayer.downloads;

@FunctionalInterface
public interface DownloadProgressListener {

    void onProgress(long beatmapsetId, long downloadedBytes, long totalBytes);

    static DownloadProgressListener noop() {
        return (id, downloaded, total) -> { };
    }
}
