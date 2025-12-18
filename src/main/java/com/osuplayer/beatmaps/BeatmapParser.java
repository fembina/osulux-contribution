package com.osuplayer.beatmaps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class BeatmapParser {

        private static final Pattern QUOTED_CONTENT_PATTERN = Pattern.compile("\"([^\"]+)\"");
        private static final List<String> FALLBACK_VIDEO_EXTENSIONS = Arrays.asList(
            ".mp4", ".mkv", ".mov", ".avi", ".wmv", ".flv", ".webm", ".mpg", ".mpeg", ".m4v");

    public SongMetadata parseSongMetadata(File osuFile) {
        if (osuFile == null || !osuFile.exists()) return null;

        String currentSection = "";
        String title = null;
        String titleUnicode = null;
        String artist = null;
        String artistUnicode = null;
        String creator = null;
        String version = null;
        String source = null;
        List<String> tags = new ArrayList<>();
        String beatmapId = null;
        String beatmapSetId = null;
        String audioFilename = null;

        try (BufferedReader reader = newBufferedReader(osuFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//")) continue;

                if (isSectionHeader(line)) {
                    currentSection = line;
                    continue;
                }

                if ("[General]".equals(currentSection)) {
                    if (line.regionMatches(true, 0, "AudioFilename:", 0, 14)) {
                        audioFilename = valueAfterColon(line);
                    }
                } else if ("[Metadata]".equals(currentSection)) {
                    if (line.regionMatches(true, 0, "TitleUnicode:", 0, 13)) {
                        titleUnicode = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Title:", 0, 6)) {
                        title = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "ArtistUnicode:", 0, 14)) {
                        artistUnicode = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Artist:", 0, 7)) {
                        artist = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Creator:", 0, 8)) {
                        creator = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Version:", 0, 8)) {
                        version = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Source:", 0, 7)) {
                        source = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "Tags:", 0, 5)) {
                        String tagString = valueAfterColon(line);
                        tags.clear();
                        if (!tagString.isEmpty()) Collections.addAll(tags, tagString.split("\\s+"));
                    } else if (line.regionMatches(true, 0, "BeatmapID:", 0, 10)) {
                        beatmapId = valueAfterColon(line);
                    } else if (line.regionMatches(true, 0, "BeatmapSetID:", 0, 13)) {
                        beatmapSetId = valueAfterColon(line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error leyendo archivo " + osuFile.getAbsolutePath() + ": " + e.getMessage());
        }

        if (title == null) title = titleUnicode;
        if (artist == null) artist = artistUnicode;

        if (title == null || artist == null || audioFilename == null) return null;

        List<String> immutableTags = tags.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tags));

        return new SongMetadata(title, titleUnicode, artist, artistUnicode, audioFilename,
                creator, version, source, immutableTags, beatmapId, beatmapSetId);
    }

    public List<File> listOsuFiles(File beatmapFolder) {
        if (beatmapFolder == null || !beatmapFolder.exists() || !beatmapFolder.isDirectory()) {
            return Collections.emptyList();
        }
        File[] osuFiles = beatmapFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".osu"));
        if (osuFiles == null || osuFiles.length == 0) return Collections.emptyList();
        return Arrays.asList(osuFiles);
    }

    public String findCoverImagePath(File beatmapFolder) {
        for (File osuFile : listOsuFiles(beatmapFolder)) {
            String bg = parseBackground(osuFile);
            if (bg == null) continue;
            File imageFile = new File(beatmapFolder, bg);
            if (imageFile.exists()) return imageFile.getAbsolutePath();
        }
        return null;
    }

    public String findVideoPath(File beatmapFolder) {
        if (beatmapFolder == null || !beatmapFolder.isDirectory()) return null;

        for (File osuFile : listOsuFiles(beatmapFolder)) {
            VideoEvent event = parseVideoEvent(osuFile);
            if (event == null || event.filename == null) continue;
            File videoFile = new File(beatmapFolder, event.filename);
            if (videoFile.exists()) return videoFile.getAbsolutePath();
        }

        File[] fallbackVideos = beatmapFolder.listFiles((dir, name) -> isLikelyVideo(name));
        if (fallbackVideos != null && fallbackVideos.length > 0) {
            return fallbackVideos[0].getAbsolutePath();
        }
        return null;
    }

    public String parseBackground(File osuFile) {
        if (osuFile == null || !osuFile.exists()) return null;

        try (BufferedReader reader = newBufferedReader(osuFile)) {
            String line;
            boolean inEventsSection = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals("[Events]")) {
                    inEventsSection = true;
                    continue;
                }
                if (inEventsSection) {
                    if (line.startsWith("0,")) {
                        String[] parts = line.split(",");
                        if (parts.length >= 3) return parts[2].replace("\"", "").trim();
                    }
                    if (isSectionHeader(line)) break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error leyendo archivo " + osuFile.getAbsolutePath() + ": " + e.getMessage());
        }
        return null;
    }

    public VideoEvent parseVideoEvent(File osuFile) {
        if (osuFile == null || !osuFile.exists()) return null;

        try (BufferedReader reader = newBufferedReader(osuFile)) {
            String line;
            boolean inEventsSection = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("//")) continue;

                if (line.equals("[Events]")) {
                    inEventsSection = true;
                    continue;
                }

                if (inEventsSection) {
                    if (isSectionHeader(line)) break;

                    if (line.regionMatches(true, 0, "Video", 0, 5)) {
                        String filename = extractVideoFilename(line);
                        long offset = extractVideoOffset(line);
                        return new VideoEvent(filename, offset);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error leyendo archivo " + osuFile.getAbsolutePath() + ": " + e.getMessage());
        }
        return null;
    }

    public String parseVideo(File osuFile) {
        VideoEvent event = parseVideoEvent(osuFile);
        return event == null ? null : event.filename;
    }

    private String extractVideoFilename(String line) {
        Matcher matcher = QUOTED_CONTENT_PATTERN.matcher(line);
        if (matcher.find()) return matcher.group(1);

        String[] parts = line.split(",", 3);
        if (parts.length >= 3) return parts[2].replace("\"", "").trim();
        return null;
    }

    private long extractVideoOffset(String line) {
        String[] parts = line.split(",", 3);
        if (parts.length < 2) return 0;
        try {
            return Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BufferedReader newBufferedReader(File osuFile) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(new FileInputStream(osuFile), decoder));
    }

    private boolean isLikelyVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : FALLBACK_VIDEO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public static final class VideoEvent {
        public final String filename;
        public final long offsetMillis;

        public VideoEvent(String filename, long offsetMillis) {
            this.filename = filename;
            this.offsetMillis = offsetMillis;
        }
    }

    private boolean isSectionHeader(String line) {
        return line.startsWith("[") && line.endsWith("]");
    }

    private String valueAfterColon(String line) {
        int idx = line.indexOf(':');
        if (idx == -1 || idx == line.length() - 1) return "";
        return line.substring(idx + 1).trim();
    }

    public static class SongMetadata {
        public final String title;
        public final String titleUnicode;
        public final String artist;
        public final String artistUnicode;
        public final String audioFilename;
        public final String creator;
        public final String version;
        public final String source;
        public final List<String> tags;
        public final String beatmapId;
        public final String beatmapSetId;

        public SongMetadata(String title, String titleUnicode, String artist, String artistUnicode,
                            String audioFilename, String creator, String version, String source,
                            List<String> tags, String beatmapId, String beatmapSetId) {
            this.title = title;
            this.titleUnicode = titleUnicode;
            this.artist = artist;
            this.artistUnicode = artistUnicode;
            this.audioFilename = audioFilename;
            this.creator = creator;
            this.version = version;
            this.source = source;
            this.tags = tags;
            this.beatmapId = beatmapId;
            this.beatmapSetId = beatmapSetId;
        }
    }
}
