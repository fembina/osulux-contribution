package com.osuplayer.beatmaps.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.osuplayer.downloads.BeatmapArchiveExtractor;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.osu.OsuApiClient;

public class OsuBeatmapDownloadService {

    private final OsuApiClient apiClient;
    private final BeatmapArchiveExtractor archiveExtractor = new BeatmapArchiveExtractor();

    public OsuBeatmapDownloadService(OsuApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public DownloadResult downloadAndExtract(OsuApiClient.BeatmapsetSummary summary,
                                             Path songsDirectory,
                                             boolean includeVideo,
                                             DownloadProgressListener listener) throws IOException {
        if (summary == null) throw new IllegalArgumentException("El resumen del beatmapset no puede ser nulo.");
        if (songsDirectory == null || !Files.isDirectory(songsDirectory)) {
            throw new IOException("Debes seleccionar una carpeta de canciones válida antes de descargar.");
        }

        Path oszFile = apiClient.downloadBeatmapset(summary.id(), includeVideo, listener);
        Path targetFolder = archiveExtractor.extract(oszFile, songsDirectory, summary.displayName(), summary.id());
        return new DownloadResult(targetFolder);
    }

    public record DownloadResult(Path extractedFolder) {}
}
