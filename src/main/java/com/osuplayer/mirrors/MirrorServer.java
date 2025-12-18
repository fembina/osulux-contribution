package com.osuplayer.mirrors;

import java.io.IOException;
import java.nio.file.Path;

import com.osuplayer.osu.OsuApiClient;
import com.osuplayer.downloads.DownloadProgressListener;

public interface MirrorServer {

    String id();

    String displayName();

    MirrorSearchResult search(String query,
                              OsuApiClient.BeatmapMode mode,
                              OsuApiClient.BeatmapStatus status,
                              int pageIndex,
                              int pageSize) throws IOException;

    Path download(long beatmapsetId, DownloadProgressListener listener) throws IOException;
}
