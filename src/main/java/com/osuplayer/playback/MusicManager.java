package com.osuplayer.playback;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.osuplayer.beatmaps.BeatmapParser;
import com.osuplayer.config.ConfigManager;
import com.osuplayer.config.SongLibraryCache;
import com.osuplayer.history.HistoryManager;
import com.osuplayer.lang.I18n;

import javafx.scene.image.Image;

public class MusicManager {

    private static final double FOLDER_SCAN_WEIGHT = 0.85;
    private static final double ENTRY_INTEGRATION_WEIGHT = 1.0 - FOLDER_SCAN_WEIGHT;

    private final ConfigManager configManager;
    private final Map<String, String> songs = new LinkedHashMap<>();
    private String lastFolderPath;
    private FolderSnapshot lastLoadedSnapshot;

    private final Map<String, String> songBaseFolders = new HashMap<>();
    private final Map<String, List<String>> songCreators = new HashMap<>();
    private final Map<String, List<String>> songTags = new HashMap<>();
    private final Map<String, String> songVideoPaths = new HashMap<>();
    private final Map<String, Long> songVideoOffsets = new HashMap<>();
    private final Map<String, String> songBackgroundPaths = new HashMap<>();
    private final Map<String, SongMetadataDetails> songMetadataDetails = new HashMap<>();
    private final Map<String, SongDisplayParts> songDisplayParts = new HashMap<>();
    private final Map<String, Integer> baseDisplayCounts = new HashMap<>();
    private final Map<String, Integer> canonicalDisplayCounters = new HashMap<>();
    private final Map<String, String> displayNameToBaseDisplay = new HashMap<>();
    private final Map<String, String> displayNameToCanonicalKey = new HashMap<>();
    private final Map<String, Integer> folderDifficultyCounts = new HashMap<>();
    private int totalDifficultyCount;

    private final HistoryManager historyManager = new HistoryManager();
    private final BeatmapParser beatmapParser = new BeatmapParser();

    public MusicManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public interface LoadingProgressCallback {
        void onProgress(double progress, String currentItem);
    }

    private void resetLibraryState() {
        songs.clear();
        songBaseFolders.clear();
        songTags.clear();
        songCreators.clear();
        songVideoPaths.clear();
        songBackgroundPaths.clear();
        songMetadataDetails.clear();
        songVideoOffsets.clear();
        songDisplayParts.clear();
        baseDisplayCounts.clear();
        canonicalDisplayCounters.clear();
        displayNameToBaseDisplay.clear();
        displayNameToCanonicalKey.clear();
        folderDifficultyCounts.clear();
        totalDifficultyCount = 0;
    }

    public Map<String, String> loadSongsFromFolder(File folder) {
        return loadSongsFromFolder(folder, null);
    }

    public Map<String, String> loadSongsFromFolder(File folder, LoadingProgressCallback progressCallback) {
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            resetLibraryState();
            lastLoadedSnapshot = null;
            lastFolderPath = null;
            return songs;
        }

        String normalizedFolderPath = normalizeFolderPath(folder.getAbsolutePath());
        FolderSnapshot snapshot = captureFolderSnapshot(folder);

        if (lastLoadedSnapshot != null && lastLoadedSnapshot.equals(snapshot) && !songs.isEmpty()) {
            notifyProgress(progressCallback, 1.0, I18n.tr("Completado"));
            return songs;
        }

        SongLibraryCache cache = configManager == null ? null : configManager.loadSongLibraryCache();
        if (cache != null && cache.matches(normalizedFolderPath, snapshot.folderModifiedTimes(), snapshot.rootLastModified())) {
            resetLibraryState();
            applyCachedLibrary(cache, progressCallback);
            folderDifficultyCounts.clear();
            folderDifficultyCounts.putAll(cache.folderDifficultyCounts());
            totalDifficultyCount = cache.totalDifficultyCount();
            lastLoadedSnapshot = snapshot;
            lastFolderPath = normalizedFolderPath;
            notifyProgress(progressCallback, 1.0, I18n.tr("Completado"));
            return songs;
        }

        resetLibraryState();

        File[] beatmapFolders = folder.listFiles(File::isDirectory);
        int totalFolders = beatmapFolders == null ? 0 : beatmapFolders.length;
        if (beatmapFolders == null) {
            notifyProgress(progressCallback, 1.0, "");
            return songs;
        }

        List<SongEntry> finalEntries = new ArrayList<>();
        int processedFolders = 0;

        for (File beatmapFolder : beatmapFolders) {
            processedFolders++;
            double folderProgress = totalFolders <= 0
                ? 0
                : (processedFolders / (double) totalFolders) * FOLDER_SCAN_WEIGHT;
            notifyProgress(progressCallback, folderProgress, I18n.tr("Carpeta") + ": " + beatmapFolder.getName());
            List<File> osuFiles = beatmapParser.listOsuFiles(beatmapFolder);
            if (osuFiles.isEmpty()) {
                continue;
            }
            totalDifficultyCount += osuFiles.size();
            String normalizedFolder = normalizeFolderPath(beatmapFolder.getAbsolutePath());
            folderDifficultyCounts.put(normalizedFolder, osuFiles.size());
            finalEntries.addAll(buildEntriesFromOsuFiles(beatmapFolder, osuFiles));
        }

        integrateEntries(finalEntries, progressCallback, true);
        notifyProgress(progressCallback, 1.0, I18n.tr("Completado"));
        lastLoadedSnapshot = snapshot;
        lastFolderPath = normalizedFolderPath;
        persistLibraryCache(normalizedFolderPath, snapshot, finalEntries);
        return songs;
    }

    public List<String> importBeatmapFolder(File beatmapFolder) {
        if (beatmapFolder == null || !beatmapFolder.isDirectory()) {
            return Collections.emptyList();
        }
        List<File> osuFiles = beatmapParser.listOsuFiles(beatmapFolder);
        if (osuFiles.isEmpty()) {
            return Collections.emptyList();
        }
        List<SongEntry> entries = buildEntriesFromOsuFiles(beatmapFolder, osuFiles);
        List<String> added = new ArrayList<>();
        for (SongEntry entry : entries) {
            String displayName = insertSongEntry(entry, true);
            if (displayName != null) {
                added.add(displayName);
            }
        }
        String normalizedFolder = normalizeFolderPath(beatmapFolder.getAbsolutePath());
        folderDifficultyCounts.merge(normalizedFolder, osuFiles.size(), Integer::sum);
        totalDifficultyCount += osuFiles.size();
        if (!added.isEmpty()) {
            refreshCacheFromCurrentState();
        }
        return added;
    }

    private List<SongEntry> buildEntriesFromOsuFiles(File beatmapFolder, List<File> osuFiles) {
        Map<File, BeatmapParser.SongMetadata> metadataByFile = new LinkedHashMap<>();
        for (File osuFile : osuFiles) {
            metadataByFile.put(osuFile, beatmapParser.parseSongMetadata(osuFile));
        }

        String folderSetId = extractFolderSetId(beatmapFolder);

        File preferredOsu = null;
        for (File osuFile : osuFiles) {
            BeatmapParser.SongMetadata meta = metadataByFile.get(osuFile);
            if (isValidBeatmapId(meta)) {
                preferredOsu = osuFile;
                break;
            }
        }
        if (preferredOsu == null) {
            for (File osuFile : osuFiles) {
                if (metadataByFile.get(osuFile) != null) {
                    preferredOsu = osuFile;
                    break;
                }
            }
        }

        Map<String, List<SongVariant>> variantsByBase = new HashMap<>();

        for (File osuFile : osuFiles) {
            BeatmapParser.SongMetadata meta = metadataByFile.get(osuFile);
            File metadataSource = osuFile;

            if (preferredOsu != null && !isValidBeatmapId(meta)) {
                BeatmapParser.SongMetadata preferredMeta = metadataByFile.get(preferredOsu);
                if (preferredMeta != null) {
                    meta = preferredMeta;
                    metadataSource = preferredOsu;
                }
            }

            if (meta == null || meta.audioFilename == null) {
                continue;
            }

            String beatmapSetId = firstValidId(meta.beatmapSetId, folderSetId);
            String beatmapId = firstValidId(meta.beatmapId, beatmapSetId);

            File audioFile = new File(beatmapFolder, meta.audioFilename);
            if (!audioFile.exists()) {
                continue;
            }

            BeatmapParser.VideoEvent videoEvent = beatmapParser.parseVideoEvent(metadataSource);
            String videoPath = null;
            long videoOffset = 0;
            if (videoEvent != null && videoEvent.filename != null) {
                File videoFile = new File(beatmapFolder, videoEvent.filename);
                if (videoFile.exists()) {
                    videoPath = videoFile.getAbsolutePath();
                    videoOffset = videoEvent.offsetMillis;
                }
            }

            String backgroundFilename = beatmapParser.parseBackground(metadataSource);
            String backgroundPath = null;
            if (backgroundFilename != null) {
                File backgroundFile = new File(beatmapFolder, backgroundFilename);
                if (backgroundFile.exists()) {
                    backgroundPath = backgroundFile.getAbsolutePath();
                }
            }

            String mapper = resolveMapper(meta, beatmapFolder);
            String baseName = meta.artist + " - " + meta.title;
            List<String> creators = (mapper == null || mapper.isBlank())
                ? Collections.emptyList()
                : Collections.singletonList(mapper);

            SongVariant variant = new SongVariant(
                baseName,
                meta.title,
                meta.artist,
                sanitizeDifficulty(meta.version),
                mapper,
                audioFile.getAbsolutePath(),
                videoPath,
                backgroundPath,
                beatmapFolder.getAbsolutePath(),
                meta.tags,
                creators,
                beatmapId,
                beatmapSetId,
                meta.source,
                videoOffset
            );

            variantsByBase.computeIfAbsent(baseName, key -> new ArrayList<>()).add(variant);
        }

        List<SongEntry> entries = new ArrayList<>();
        for (List<SongVariant> variants : variantsByBase.values()) {
            if (variants.isEmpty()) {
                continue;
            }
            Map<String, SongVariant> mediaRepresentatives = new LinkedHashMap<>();
            for (SongVariant variant : variants) {
                String mediaKey = buildMediaKey(variant);
                mediaRepresentatives.putIfAbsent(mediaKey, variant);
            }

            if (mediaRepresentatives.size() == 1) {
                entries.add(mediaRepresentatives.values().iterator().next().toEntry(false));
            } else {
                for (SongVariant variant : mediaRepresentatives.values()) {
                    entries.add(variant.toEntry(true));
                }
            }
        }
        return entries;
    }

    private void integrateEntries(List<SongEntry> entries,
                                  LoadingProgressCallback progressCallback,
                                  boolean reportProgress) {
        Map<String, Integer> computedBaseCounts = new HashMap<>();
        for (SongEntry entry : entries) {
            computedBaseCounts.merge(buildBaseDisplayLabel(entry), 1, Integer::sum);
        }
        baseDisplayCounts.clear();
        baseDisplayCounts.putAll(computedBaseCounts);
        canonicalDisplayCounters.clear();
        displayNameToBaseDisplay.clear();
        displayNameToCanonicalKey.clear();

        int totalEntries = entries.size();
        int processedEntries = 0;
        for (SongEntry entry : entries) {
            processedEntries++;
            if (reportProgress && totalEntries > 0) {
                double songProgress = FOLDER_SCAN_WEIGHT
                    + (processedEntries / (double) totalEntries) * ENTRY_INTEGRATION_WEIGHT;
                notifyProgress(progressCallback, songProgress, "♫ " + entry.baseName);
            }
            insertSongEntry(entry, false);
        }
    }

    private void applyCachedLibrary(SongLibraryCache cache, LoadingProgressCallback progressCallback) {
        List<SongEntry> entries = new ArrayList<>(cache.entries().size());
        for (SongLibraryCache.CachedSongEntry cached : cache.entries()) {
            entries.add(new SongEntry(
                cached.baseName(),
                cached.title(),
                cached.artist(),
                cached.difficultyName(),
                cached.mapper(),
                cached.audioPath(),
                cached.videoPath(),
                cached.videoOffsetMillis(),
                cached.backgroundPath(),
                cached.baseFolder(),
                cached.tags(),
                cached.creators(),
                cached.showDifficulty(),
                cached.beatmapId(),
                cached.beatmapSetId(),
                cached.source()
            ));
        }
        integrateEntries(entries, progressCallback, false);
    }

    private String insertSongEntry(SongEntry entry, boolean incrementBaseCounter) {
        if (entry == null) {
            return null;
        }
        String baseDisplay = buildBaseDisplayLabel(entry);
        if (incrementBaseCounter) {
            baseDisplayCounts.merge(baseDisplay, 1, Integer::sum);
        }
        boolean duplicated = baseDisplayCounts.getOrDefault(baseDisplay, 0) > 1;
        String canonicalKey = duplicated
            ? baseDisplay + " (" + canonicalMapperLabel(entry.mapper) + ")"
            : baseDisplay;
        int occurrence = canonicalDisplayCounters.merge(canonicalKey, 1, Integer::sum);
        String finalDisplayName = occurrence > 1 ? canonicalKey + " [" + occurrence + "]" : canonicalKey;

        songs.put(finalDisplayName, entry.audioPath);
        String normalizedFolder = normalizeFolderPath(entry.baseFolder);
        songBaseFolders.put(finalDisplayName, normalizedFolder);
        songTags.put(finalDisplayName, entry.tags == null ? Collections.emptyList() : entry.tags);
        songCreators.put(finalDisplayName, entry.creators == null ? Collections.emptyList() : entry.creators);
        songVideoPaths.put(finalDisplayName, entry.videoPath == null ? "" : entry.videoPath);
        songVideoOffsets.put(finalDisplayName, entry.videoOffsetMillis);
        songBackgroundPaths.put(finalDisplayName, entry.backgroundPath == null ? "" : entry.backgroundPath);
        songMetadataDetails.put(finalDisplayName, entry.toMetadataDetails());

        boolean showDifficultySegment = entry.showDifficulty
            && entry.difficultyName != null
            && !entry.difficultyName.isBlank();
        String difficultySegment = showDifficultySegment ? entry.difficultyName.trim() : null;
        String mapperSegment = duplicated ? entry.mapper : null;
        String occurrenceSuffixText = occurrence > 1 ? "[" + occurrence + "]" : null;
        songDisplayParts.put(finalDisplayName, new SongDisplayParts(
            entry.baseName,
            difficultySegment,
            mapperSegment,
            occurrenceSuffixText));

        displayNameToBaseDisplay.put(finalDisplayName, baseDisplay);
        displayNameToCanonicalKey.put(finalDisplayName, canonicalKey);
        return finalDisplayName;
    }

    private void notifyProgress(LoadingProgressCallback callback, double progress, String currentItem) {
        if (callback == null) {
            return;
        }
        double clamped = Math.max(0d, Math.min(1d, progress));
        callback.onProgress(clamped, currentItem);
    }

    public int getLoadedSongCount() {
        return songs.size();
    }

    public int getLoadedDifficultyCount() {
        return totalDifficultyCount;
    }

    public String getSongPath(String songName) {
        return songs.getOrDefault(songName, null);
    }

    public void setLastFolderPath(String path) {
        this.lastFolderPath = path == null ? null : normalizeFolderPath(path);
    }

    public String getLastFolderPath() {
        return lastFolderPath;
    }

    public String getSongBaseFolder(String songName) {
        return songBaseFolders.getOrDefault(songName, null);
    }

    public String getCoverImagePath(String songName) {
        String stored = songBackgroundPaths.get(songName);
        if (stored != null) return stored.isEmpty() ? null : stored;

        String baseFolder = getSongBaseFolder(songName);
        if (baseFolder == null) return null;

        return beatmapParser.findCoverImagePath(new File(baseFolder));
    }

    public String getVideoPath(String songName) {
        String stored = songVideoPaths.get(songName);
        if (stored != null) return stored.isEmpty() ? null : stored;

        String baseFolder = getSongBaseFolder(songName);
        if (baseFolder == null) return null;

        return beatmapParser.findVideoPath(new File(baseFolder));
    }

    public long getVideoOffset(String songName) {
        return songVideoOffsets.getOrDefault(songName, 0L);
    }

    public SongMetadataDetails getMetadata(String songName) {
        return songMetadataDetails.get(songName);
    }

    public SongDisplayParts getDisplayParts(String songName) {
        return songDisplayParts.get(songName);
    }

    private String resolveMapper(BeatmapParser.SongMetadata meta, File beatmapFolder) {
        if (meta.creator != null && !meta.creator.isBlank()) return meta.creator.trim();
        return beatmapFolder != null ? beatmapFolder.getName() : "Mapper desconocido";
    }

    private String canonicalMapperLabel(String mapper) {
        if (mapper == null || mapper.isBlank()) {
            return "Mapper desconocido";
        }
        return mapper.trim();
    }

    private String sanitizeDifficulty(String version) {
        if (version == null || version.trim().isEmpty()) return "Unknown";
        return version.trim();
    }

    private String buildBaseDisplayLabel(SongEntry entry) {
        String label = entry.baseName;
        if (entry.showDifficulty && entry.difficultyName != null && !entry.difficultyName.isBlank()) {
            label += " / " + entry.difficultyName;
        }
        return label;
    }

    private String buildMediaKey(SongVariant variant) {
        String video = variant.videoPath == null ? "" : variant.videoPath;
        return variant.audioPath + "::" + video;
    }

    private String firstValidId(String primary, String fallback) {
        if (isValidBeatmapId(primary)) return primary.trim();
        if (isValidBeatmapId(fallback)) return fallback.trim();
        return primary != null ? primary.trim() : null;
    }

    private String extractFolderSetId(File beatmapFolder) {
        if (beatmapFolder == null) return null;
        String name = beatmapFolder.getName();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        String result = digits.toString().trim();
        if (result.isEmpty()) return null;
        return result;
    }

    private boolean isValidBeatmapId(BeatmapParser.SongMetadata metadata) {
        if (metadata == null) return false;
        return isValidBeatmapId(metadata.beatmapId);
    }

    private boolean isValidBeatmapId(String beatmapId) {
        if (beatmapId == null) return false;
        String trimmed = beatmapId.trim();
        if (trimmed.isEmpty()) return false;
        return !("0".equals(trimmed) || "-1".equals(trimmed));
    }

    private String normalizeFolderPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return folderPath;
        }
        try {
            return Path.of(folderPath).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException ex) {
            return folderPath;
        }
    }

    private void removeSongInternal(String displayName) {
        if (displayName == null) {
            return;
        }
        songs.remove(displayName);
        songBaseFolders.remove(displayName);
        songTags.remove(displayName);
        songCreators.remove(displayName);
        songVideoPaths.remove(displayName);
        songVideoOffsets.remove(displayName);
        songBackgroundPaths.remove(displayName);
        songMetadataDetails.remove(displayName);
        songDisplayParts.remove(displayName);

        String baseDisplay = displayNameToBaseDisplay.remove(displayName);
        if (baseDisplay != null) {
            baseDisplayCounts.computeIfPresent(baseDisplay, (key, value) -> value <= 1 ? null : value - 1);
        }
        String canonicalKey = displayNameToCanonicalKey.remove(displayName);
        if (canonicalKey != null) {
            canonicalDisplayCounters.computeIfPresent(canonicalKey, (key, value) -> value <= 1 ? null : value - 1);
        }
    }

    private void pruneHistoryEntries(Set<String> removedSongs) {
        if (removedSongs == null || removedSongs.isEmpty()) {
            return;
        }
        List<String> currentHistory = historyManager.getHistory();
        if (currentHistory.isEmpty()) {
            return;
        }
        int currentIndex = historyManager.getIndex();
        List<String> filtered = new ArrayList<>();
        for (String song : currentHistory) {
            if (!removedSongs.contains(song)) {
                filtered.add(song);
            }
        }
        if (filtered.size() == currentHistory.size()) {
            return;
        }
        int newIndex = currentIndex;
        if (filtered.isEmpty()) {
            newIndex = -1;
        } else if (currentIndex >= filtered.size()) {
            newIndex = filtered.size() - 1;
        } else if (currentIndex < 0) {
            newIndex = filtered.size() - 1;
        }
        historyManager.setHistory(filtered, newIndex);
    }

    private void persistLibraryCache(String normalizedFolderPath,
                                     FolderSnapshot snapshot,
                                     List<SongEntry> entries) {
        if (configManager == null || snapshot == null || normalizedFolderPath == null) {
            return;
        }
        List<SongLibraryCache.FolderSignature> signatures = new ArrayList<>();
        snapshot.folderModifiedTimes().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> signatures.add(new SongLibraryCache.FolderSignature(entry.getKey(), entry.getValue())));

        List<SongLibraryCache.CachedSongEntry> cachedEntries = new ArrayList<>(entries.size());
        for (SongEntry entry : entries) {
            cachedEntries.add(new SongLibraryCache.CachedSongEntry(
                entry.baseName,
                entry.title,
                entry.artist,
                entry.difficultyName,
                entry.mapper,
                entry.audioPath,
                entry.videoPath,
                entry.videoOffsetMillis,
                entry.backgroundPath,
                normalizeFolderPath(entry.baseFolder),
                entry.tags == null ? List.of() : entry.tags,
                entry.creators == null ? List.of() : entry.creators,
                entry.showDifficulty,
                entry.beatmapId,
                entry.beatmapSetId,
                entry.source
            ));
        }
        Map<String, Integer> difficultyCopy = new HashMap<>(folderDifficultyCounts);
        SongLibraryCache cache = new SongLibraryCache(
            normalizedFolderPath,
            snapshot.rootLastModified(),
            signatures,
            cachedEntries,
            difficultyCopy,
            totalDifficultyCount
        );
        configManager.saveSongLibraryCache(cache);
    }

    private List<SongEntry> exportCurrentEntriesForCache() {
        List<SongEntry> entries = new ArrayList<>(songs.size());
        for (Map.Entry<String, String> songEntry : songs.entrySet()) {
            String displayName = songEntry.getKey();
            SongMetadataDetails metadata = songMetadataDetails.get(displayName);
            SongDisplayParts parts = songDisplayParts.get(displayName);
            if (metadata == null || parts == null || parts.baseText == null) {
                continue;
            }
            boolean showDifficulty = parts.difficultyText != null;
            List<String> tags = songTags.get(displayName);
            if (tags == null) {
                tags = Collections.emptyList();
            }
            List<String> creators = songCreators.get(displayName);
            if (creators == null) {
                creators = Collections.emptyList();
            }
            entries.add(new SongEntry(
                parts.baseText,
                metadata.title,
                metadata.artist,
                metadata.difficulty,
                metadata.mapper,
                songEntry.getValue(),
                songVideoPaths.get(displayName),
                songVideoOffsets.getOrDefault(displayName, 0L),
                songBackgroundPaths.get(displayName),
                songBaseFolders.get(displayName),
                tags,
                creators,
                showDifficulty,
                metadata.beatmapId,
                metadata.beatmapSetId,
                metadata.source
            ));
        }
        return entries;
    }

    private void refreshCacheFromCurrentState() {
        if (configManager == null) {
            return;
        }
        if (lastFolderPath == null || lastFolderPath.isBlank()) {
            return;
        }
        File folder = new File(lastFolderPath);
        if (!folder.isDirectory()) {
            return;
        }
        FolderSnapshot snapshot = captureFolderSnapshot(folder);
        List<SongEntry> entries = exportCurrentEntriesForCache();
        persistLibraryCache(lastFolderPath, snapshot, entries);
        lastLoadedSnapshot = snapshot;
    }

    private FolderSnapshot captureFolderSnapshot(File folder) {
        Map<String, Long> modifiedMap = new HashMap<>();
        File[] folders = folder.listFiles(File::isDirectory);
        if (folders != null) {
            for (File child : folders) {
                modifiedMap.put(normalizeFolderPath(child.getAbsolutePath()), child.lastModified());
            }
        }
        long rootModified = folder.lastModified();
        return new FolderSnapshot(rootModified, modifiedMap);
    }

    private record FolderSnapshot(long rootLastModified, Map<String, Long> folderModifiedTimes) {
        FolderSnapshot {
            folderModifiedTimes = folderModifiedTimes == null ? Map.of() : Map.copyOf(folderModifiedTimes);
        }
    }

    private static class SongVariant {
        final String baseName;
        final String title;
        final String artist;
        final String difficultyName;
        final String mapper;
        final String audioPath;
        final String videoPath;
        final long videoOffsetMillis;
        final String backgroundPath;
        final String baseFolder;
        final List<String> tags;
        final List<String> creators;
        final String beatmapId;
        final String beatmapSetId;
        final String source;

        SongVariant(String baseName, String title, String artist, String difficultyName, String mapper,
                String audioPath, String videoPath, String backgroundPath, String baseFolder, List<String> tags,
                    List<String> creators, String beatmapId, String beatmapSetId, String source, long videoOffsetMillis) {
            this.baseName = baseName;
            this.title = title;
            this.artist = artist;
            this.difficultyName = difficultyName;
            this.mapper = mapper;
            this.audioPath = audioPath;
            this.videoPath = videoPath;
            this.videoOffsetMillis = videoOffsetMillis;
            this.backgroundPath = backgroundPath;
            this.baseFolder = baseFolder;
            this.tags = tags;
            this.creators = creators;
            this.beatmapId = beatmapId;
            this.beatmapSetId = beatmapSetId;
            this.source = source;
        }

        SongEntry toEntry(boolean showDifficulty) {
            return new SongEntry(baseName, title, artist, difficultyName, mapper, audioPath,
                videoPath, videoOffsetMillis, backgroundPath, baseFolder, tags, creators, showDifficulty, beatmapId,
                    beatmapSetId, source);
        }
    }

    private static class SongEntry {
        final String baseName;
        final String title;
        final String artist;
        final String difficultyName;
        final String mapper;
        final String audioPath;
        final String videoPath;
        final long videoOffsetMillis;
        final String backgroundPath;
        final String baseFolder;
        final List<String> tags;
        final List<String> creators;
        final boolean showDifficulty;
        final String beatmapId;
        final String beatmapSetId;
        final String source;

          SongEntry(String baseName, String title, String artist, String difficultyName, String mapper,
                String audioPath, String videoPath, long videoOffsetMillis, String backgroundPath, String baseFolder, List<String> tags,
                List<String> creators, boolean showDifficulty, String beatmapId,
                String beatmapSetId, String source) {
            this.baseName = baseName;
            this.title = title;
            this.artist = artist;
            this.difficultyName = difficultyName;
            this.mapper = mapper;
            this.audioPath = audioPath;
            this.videoPath = videoPath;
            this.videoOffsetMillis = videoOffsetMillis;
            this.backgroundPath = backgroundPath;
            this.baseFolder = baseFolder;
            this.tags = tags;
            this.creators = creators;
            this.showDifficulty = showDifficulty;
            this.beatmapId = beatmapId;
            this.beatmapSetId = beatmapSetId;
            this.source = source;
        }

        SongMetadataDetails toMetadataDetails() {
                return new SongMetadataDetails(title, artist, mapper, difficultyName,
                    beatmapId, beatmapSetId, source, audioPath,
                    videoPath, videoOffsetMillis, backgroundPath, baseFolder,
                    tags == null ? Collections.emptyList() : tags);
        }
    }

    public Image getStoryboardImage(String songName) {
        String baseFolder = getSongBaseFolder(songName);
        if (baseFolder == null) return null;

        File storyboardFile = new File(baseFolder, "storyboard.png");
        if (storyboardFile.exists()) return new Image(storyboardFile.toURI().toString());
        return null;
    }

    public List<String> getTags(String songName) {
        return songTags.getOrDefault(songName, Collections.emptyList());
    }

    public List<String> getCreators(String songName) {
        return songCreators.getOrDefault(songName, Collections.emptyList());
    }

    public List<String> searchSongs(String query) {
        if (query == null || query.isEmpty()) return new ArrayList<>(songs.keySet());

        String lowerQuery = query.toLowerCase();
        List<String> results = new ArrayList<>();

        for (String name : songs.keySet()) {
            if (name.toLowerCase().contains(lowerQuery)) {
                results.add(name);
                continue;
            }
            for (String tag : songTags.getOrDefault(name, Collections.emptyList())) {
                if (tag.toLowerCase().contains(lowerQuery)) {
                    results.add(name);
                    break;
                }
            }
        }
        return results;
    }

    
    public void addToHistory(String songName) { historyManager.addSong(songName); }
    public String getPreviousFromHistory() { return historyManager.getPrevious(); }
    public String getNextFromHistory() { return historyManager.getNext(); }
    public boolean hasPreviousInHistory() { return historyManager.hasPrevious(); }
    public boolean hasNextInHistory() { return historyManager.hasNext(); }
    public String getCurrentHistorySong() { return historyManager.getCurrent(); }
    public void clearHistory() { historyManager.clear(); }
    public List<String> getHistory() { return historyManager.getHistory(); }
    public int getHistoryIndex() { return historyManager.getIndex(); }
    public void setHistoryIndex(int index) { historyManager.setIndex(index); }
    public void setHistory(List<String> history, int index) { historyManager.setHistory(history, index); }

    public List<String> removeSongsByFolder(Path folder) {
        if (folder == null) {
            return Collections.emptyList();
        }
        String normalized = normalizeFolderPath(folder.toString());
        List<String> targets = new ArrayList<>();
        for (Map.Entry<String, String> entry : songBaseFolders.entrySet()) {
            if (normalized.equals(entry.getValue())) {
                targets.add(entry.getKey());
            }
        }
        if (targets.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> removed = new ArrayList<>(targets.size());
        for (String displayName : targets) {
            removed.add(displayName);
            removeSongInternal(displayName);
        }
        Integer difficulties = folderDifficultyCounts.remove(normalized);
        if (difficulties != null && difficulties > 0) {
            totalDifficultyCount = Math.max(0, totalDifficultyCount - difficulties);
        }
        pruneHistoryEntries(Set.copyOf(removed));
        if (!removed.isEmpty()) {
            refreshCacheFromCurrentState();
        }
        return removed;
    }

    public static class SongMetadataDetails {
        public final String title;
        public final String artist;
        public final String mapper;
        public final String difficulty;
        public final String beatmapId;
        public final String beatmapSetId;
        public final String source;
        public final String audioPath;
        public final String videoPath;
        public final long videoOffsetMillis;
        public final String backgroundPath;
        public final String baseFolder;
        public final List<String> tags;

        public SongMetadataDetails(String title, String artist, String mapper, String difficulty,
                                   String beatmapId, String beatmapSetId, String source,
                       String audioPath, String videoPath, long videoOffsetMillis, String backgroundPath, String baseFolder,
                                   List<String> tags) {
            this.title = title;
            this.artist = artist;
            this.mapper = mapper;
            this.difficulty = difficulty;
            this.beatmapId = beatmapId;
            this.beatmapSetId = beatmapSetId;
            this.source = source;
            this.audioPath = audioPath;
            this.videoPath = videoPath;
            this.videoOffsetMillis = videoOffsetMillis;
            this.backgroundPath = backgroundPath;
            this.baseFolder = baseFolder;
            this.tags = tags == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(tags));
        }
    }

    public static class SongDisplayParts {
        public final String baseText;
        public final String difficultyText;
        public final String mapperText;
        public final String duplicateSuffix;

        public SongDisplayParts(String baseText,
                                String difficultyText,
                                String mapperText,
                                String duplicateSuffix) {
            this.baseText = baseText;
            this.difficultyText = normalize(difficultyText);
            this.mapperText = normalize(mapperText);
            this.duplicateSuffix = duplicateSuffix;
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

}


