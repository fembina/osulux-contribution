package com.osuplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.image.Image;

public class MusicManager {

    private final Map<String, String> songs = new LinkedHashMap<>();
    private String lastFolderPath;

    private final Map<String, String> songBaseFolders = new HashMap<>();
    private final Map<String, List<String>> songCreators = new HashMap<>();
    private final Map<String, List<String>> songTags = new HashMap<>();
    private final Map<String, String> songVideoPaths = new HashMap<>();
    private final Map<String, Long> songVideoOffsets = new HashMap<>();
    private final Map<String, String> songBackgroundPaths = new HashMap<>();
    private final Map<String, SongMetadataDetails> songMetadataDetails = new HashMap<>();

    private final HistoryManager historyManager = new HistoryManager();
    private final BeatmapParser beatmapParser = new BeatmapParser();

    public Map<String, String> loadSongsFromFolder(File folder) {
        songs.clear();
        songBaseFolders.clear();
        songTags.clear();
        songCreators.clear();
        songVideoPaths.clear();
        songBackgroundPaths.clear();
        songMetadataDetails.clear();
        songVideoOffsets.clear();

        if (folder == null || !folder.exists() || !folder.isDirectory()) return songs;

        File[] beatmapFolders = folder.listFiles(File::isDirectory);
        if (beatmapFolders == null) return songs;

        List<SongEntry> finalEntries = new ArrayList<>();

        for (File beatmapFolder : beatmapFolders) {
            List<File> osuFiles = beatmapParser.listOsuFiles(beatmapFolder);
            if (osuFiles.isEmpty()) continue;

            Map<String, List<SongVariant>> variantsByBase = new HashMap<>();

            for (File osuFile : osuFiles) {
                BeatmapParser.SongMetadata meta = beatmapParser.parseSongMetadata(osuFile);
                if (meta == null || meta.audioFilename == null) continue;

                File audioFile = new File(beatmapFolder, meta.audioFilename);
                if (!audioFile.exists()) continue;

                BeatmapParser.VideoEvent videoEvent = beatmapParser.parseVideoEvent(osuFile);
                String videoPath = null;
                long videoOffset = 0;
                if (videoEvent != null && videoEvent.filename != null) {
                    File videoFile = new File(beatmapFolder, videoEvent.filename);
                    if (videoFile.exists()) {
                        videoPath = videoFile.getAbsolutePath();
                        videoOffset = videoEvent.offsetMillis;
                    }
                }

                String backgroundFilename = beatmapParser.parseBackground(osuFile);
                String backgroundPath = null;
                if (backgroundFilename != null) {
                    File backgroundFile = new File(beatmapFolder, backgroundFilename);
                    if (backgroundFile.exists()) backgroundPath = backgroundFile.getAbsolutePath();
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
                    meta.beatmapId,
                    meta.beatmapSetId,
                    meta.source,
                    videoOffset
                );

                variantsByBase.computeIfAbsent(baseName, key -> new ArrayList<>()).add(variant);
            }

            for (List<SongVariant> variants : variantsByBase.values()) {
                if (variants.isEmpty()) continue;

                Map<String, SongVariant> mediaRepresentatives = new LinkedHashMap<>();
                for (SongVariant variant : variants) {
                    String mediaKey = buildMediaKey(variant);
                    mediaRepresentatives.putIfAbsent(mediaKey, variant);
                }

                if (mediaRepresentatives.size() == 1) {
                    finalEntries.add(mediaRepresentatives.values().iterator().next().toEntry(false));
                } else {
                    for (SongVariant variant : mediaRepresentatives.values()) {
                        finalEntries.add(variant.toEntry(true));
                    }
                }
            }
        }

        Map<String, Integer> baseDisplayCounts = new HashMap<>();
        for (SongEntry entry : finalEntries) {
            baseDisplayCounts.merge(buildBaseDisplayLabel(entry), 1, Integer::sum);
        }

        Map<String, Integer> displayOccurrences = new HashMap<>();
        for (SongEntry entry : finalEntries) {
            String baseDisplay = buildBaseDisplayLabel(entry);
            boolean duplicated = baseDisplayCounts.getOrDefault(baseDisplay, 0) > 1;
            String displayName = duplicated ? baseDisplay + " (" + entry.mapper + ")" : baseDisplay;

            int occurrence = displayOccurrences.merge(displayName, 1, Integer::sum);
            if (occurrence > 1) displayName = displayName + " [" + occurrence + "]";

            songs.put(displayName, entry.audioPath);
            songBaseFolders.put(displayName, entry.baseFolder);
            songTags.put(displayName, entry.tags);
            songCreators.put(displayName, entry.creators);
            songVideoPaths.put(displayName, entry.videoPath == null ? "" : entry.videoPath);
            songVideoOffsets.put(displayName, entry.videoOffsetMillis);
            songBackgroundPaths.put(displayName, entry.backgroundPath == null ? "" : entry.backgroundPath);
            songMetadataDetails.put(displayName, entry.toMetadataDetails());
        }
        return songs;
    }

    public String getSongPath(String songName) {
        return songs.getOrDefault(songName, null);
    }

    public void setLastFolderPath(String path) {
        this.lastFolderPath = path;
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

    private String resolveMapper(BeatmapParser.SongMetadata meta, File beatmapFolder) {
        if (meta.creator != null && !meta.creator.isBlank()) return meta.creator.trim();
        return beatmapFolder != null ? beatmapFolder.getName() : "Mapper desconocido";
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

}


