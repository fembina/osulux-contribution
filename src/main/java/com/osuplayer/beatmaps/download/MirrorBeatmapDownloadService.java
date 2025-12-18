package com.osuplayer.beatmaps.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.osuplayer.downloads.BeatmapArchiveExtractor;
import com.osuplayer.downloads.DownloadProgressListener;
import com.osuplayer.mirrors.MirrorServer;
import com.osuplayer.osu.OsuApiClient;

public class MirrorBeatmapDownloadService {

    private final List<MirrorServer> servers;
    private final BeatmapArchiveExtractor archiveExtractor = new BeatmapArchiveExtractor();

    public MirrorBeatmapDownloadService(List<MirrorServer> servers) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("Debes proporcionar al menos un mirror.");
        }
        this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
    }

    public MirrorDownloadResult downloadAndExtract(OsuApiClient.BeatmapsetSummary summary,
                                                   Path songsDirectory,
                                                   MirrorServer preferred,
                                                   DownloadProgressListener listener) throws IOException {
        if (summary == null) {
            throw new IllegalArgumentException("No hay beatmap seleccionado.");
        }
        if (songsDirectory == null || !Files.isDirectory(songsDirectory)) {
            throw new IOException("Debes seleccionar una carpeta de canciones válida antes de descargar.");
        }

        List<String> failures = new ArrayList<>();
        for (MirrorServer server : orderedServers(preferred)) {
            try {
                Path oszFile = server.download(summary.id(), listener);
                Path targetFolder = archiveExtractor.extract(oszFile, songsDirectory, summary.displayName(), summary.id());
                return new MirrorDownloadResult(targetFolder, server.displayName());
            } catch (IOException ex) {
                String reason = ex.getMessage();
                if (reason == null || reason.isBlank()) {
                    reason = ex.getClass().getSimpleName();
                }
                failures.add(server.displayName() + ": " + reason);
            }
        }

        throw new IOException("No se pudo descargar el beatmap desde ningún mirror:\n" + String.join("\n", failures));
    }

    private List<MirrorServer> orderedServers(MirrorServer preferred) {
        if (preferred == null) {
            return servers;
        }
        List<MirrorServer> ordered = new ArrayList<>(servers.size());
        ordered.add(preferred);
        for (MirrorServer server : servers) {
            if (!server.id().equals(preferred.id())) {
                ordered.add(server);
            }
        }
        return ordered;
    }

    public record MirrorDownloadResult(Path extractedFolder, String sourceName) {}
}
