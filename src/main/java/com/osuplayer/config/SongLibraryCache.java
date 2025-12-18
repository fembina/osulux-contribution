package com.osuplayer.config;

import java.util.List;
import java.util.Map;

public record SongLibraryCache(
    int version,
    String folderPath,
    long rootLastModified,
    List<FolderSignature> folderSignatures,
    List<CachedSongEntry> entries,
    Map<String, Integer> folderDifficultyCounts,
    int totalDifficultyCount
) {

    public static final int FORMAT_VERSION = 1;

    public SongLibraryCache {
        folderSignatures = folderSignatures == null ? List.of() : List.copyOf(folderSignatures);
        entries = entries == null ? List.of() : List.copyOf(entries);
        folderDifficultyCounts = folderDifficultyCounts == null ? Map.of() : Map.copyOf(folderDifficultyCounts);
    }

    public SongLibraryCache(String folderPath,
                            long rootLastModified,
                            List<FolderSignature> folderSignatures,
                            List<CachedSongEntry> entries,
                            Map<String, Integer> folderDifficultyCounts,
                            int totalDifficultyCount) {
        this(FORMAT_VERSION, folderPath, rootLastModified, folderSignatures, entries, folderDifficultyCounts, totalDifficultyCount);
    }

    public boolean matches(String normalizedFolder,
                           Map<String, Long> currentSignatures,
                           long currentRootLastModified) {
        if (normalizedFolder == null || folderPath == null) {
            return false;
        }
        if (!normalizedFolder.equalsIgnoreCase(folderPath)) {
            return false;
        }
        if (rootLastModified != currentRootLastModified) {
            return false;
        }
        if (folderSignatures.size() != currentSignatures.size()) {
            return false;
        }
        for (FolderSignature signature : folderSignatures) {
            Long current = currentSignatures.get(signature.path());
            if (current == null || current != signature.lastModified()) {
                return false;
            }
        }
        return true;
    }

    public record FolderSignature(String path, long lastModified) { }

    public record CachedSongEntry(
        String baseName,
        String title,
        String artist,
        String difficultyName,
        String mapper,
        String audioPath,
        String videoPath,
        long videoOffsetMillis,
        String backgroundPath,
        String baseFolder,
        List<String> tags,
        List<String> creators,
        boolean showDifficulty,
        String beatmapId,
        String beatmapSetId,
        String source
    ) {
        public CachedSongEntry {
            tags = tags == null ? List.of() : List.copyOf(tags);
            creators = creators == null ? List.of() : List.copyOf(creators);
        }
    }
}
