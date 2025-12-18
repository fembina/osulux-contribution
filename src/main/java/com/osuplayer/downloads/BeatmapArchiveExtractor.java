package com.osuplayer.downloads;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

public class BeatmapArchiveExtractor {

    public Path extract(Path archiveFile, Path songsDirectory, String displayName, long beatmapsetId) throws IOException {
        if (archiveFile == null || !Files.exists(archiveFile)) {
            throw new IOException("El archivo .osz no existe o es inaccesible.");
        }
        if (songsDirectory == null || !Files.isDirectory(songsDirectory)) {
            throw new IOException("Debes seleccionar una carpeta de canciones válida antes de descargar.");
        }

        Path targetFolder = resolveTargetFolder(songsDirectory, displayName, beatmapsetId);
        try {
            ExtractionStats stats = unzip(archiveFile, targetFolder);
            if (stats.filesExtracted == 0) {
                deleteDirectoryQuietly(targetFolder);
                throw new IOException("El archivo descargado no contenía archivos de beatmap. Es posible que la descarga haya fallado.");
            }
            return targetFolder;
        } catch (IOException ex) {
            logExtractionFailure(beatmapsetId, ex);
            deleteDirectoryQuietly(targetFolder);
            throw ex;
        } finally {
            Files.deleteIfExists(archiveFile);
        }
    }

    private void logExtractionFailure(long beatmapsetId, IOException ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Osulux] Error al extraer beatmapset ").append(beatmapsetId)
                .append(':').append(System.lineSeparator())
                .append(ex).append(System.lineSeparator());
        for (StackTraceElement element : ex.getStackTrace()) {
            sb.append("    at ").append(element).append(System.lineSeparator());
        }
        try {
            Files.writeString(Path.of("osulux-error.log"), sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
        System.err.println(sb);
    }

    private Path resolveTargetFolder(Path songsDirectory, String displayName, long beatmapsetId) throws IOException {
        String baseName = (displayName == null || displayName.isBlank())
                ? ("beatmapset-" + beatmapsetId)
                : displayName;
        baseName = sanitize(baseName) + " [" + beatmapsetId + "]";

        Path candidate = songsDirectory.resolve(baseName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = songsDirectory.resolve(baseName + "-" + suffix++);
        }
        Files.createDirectories(candidate);
        return candidate;
    }

    private ExtractionStats unzip(Path source, Path destination) throws IOException {
        IOException lastError = null;
        boolean firstAttempt = true;
        for (Charset charset : ZIP_CHARSET_CANDIDATES) {
            if (!firstAttempt) {
                resetDestinationFolder(destination);
            }
            firstAttempt = false;
            try {
                return unzipWithCharset(source, destination, charset);
            } catch (CharacterCodingException | IllegalArgumentException ex) {
                lastError = new IOException("No se pudo leer el ZIP con la codificación " + charset.displayName(), ex);
            }
        }

        for (Charset charset : ZIP_CHARSET_CANDIDATES) {
            resetDestinationFolder(destination);
            try {
                return unzipWithZip4j(source, destination, charset);
            } catch (ZipException | CharacterCodingException | IllegalArgumentException ex) {
                lastError = new IOException("No se pudo extraer el ZIP con zip4j usando " + charset.displayName(), ex);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("No se pudo extraer el ZIP descargado.");
    }

    private ExtractionStats unzipWithCharset(Path source, Path destination, Charset charset) throws IOException {
        int files = 0;
        long bytes = 0;
        Map<String, String> renamedFiles = new HashMap<>();
        Map<String, String> renamedPaths = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(source), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName() == null || entry.getName().isBlank()) {
                    continue;
                }
                ResolvedEntry resolvedEntry = resolveEntryPath(destination, entry.getName());
                Path resolved = resolvedEntry.path();
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Path parent = resolved.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream output = Files.newOutputStream(resolved)) {
                    long copied = zis.transferTo(output);
                    bytes += Math.max(0, copied);
                }
                if (resolvedEntry.originalFileName() != null
                        && resolvedEntry.sanitizedFileName() != null
                        && !resolvedEntry.originalFileName().equals(resolvedEntry.sanitizedFileName())) {
                    renamedFiles.putIfAbsent(resolvedEntry.originalFileName(), resolvedEntry.sanitizedFileName());
                }
                if (resolvedEntry.originalRelativePath() != null
                        && resolvedEntry.sanitizedRelativePath() != null
                        && !resolvedEntry.originalRelativePath().equals(resolvedEntry.sanitizedRelativePath())) {
                    renamedPaths.putIfAbsent(resolvedEntry.originalRelativePath(), resolvedEntry.sanitizedRelativePath());
                }
                files++;
                zis.closeEntry();
            }
        }
        rewriteBeatmapReferences(destination, renamedFiles, renamedPaths);
        fixVideoFilenameFirstLetter(destination);
        return new ExtractionStats(files, bytes);
    }

    private ExtractionStats unzipWithZip4j(Path source, Path destination, Charset charset) throws IOException {
        int files = 0;
        long bytes = 0;
        Map<String, String> renamedFiles = new HashMap<>();
        Map<String, String> renamedPaths = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(source.toFile())) {
            zipFile.setCharset(charset);
            List<FileHeader> headers = zipFile.getFileHeaders();
            for (FileHeader header : headers) {
                String name = header.getFileName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                ResolvedEntry resolvedEntry = resolveEntryPath(destination, name);
                Path resolved = resolvedEntry.path();
                if (header.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Path parent = resolved.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream input = zipFile.getInputStream(header);
                     OutputStream output = Files.newOutputStream(resolved)) {
                    long copied = input.transferTo(output);
                    bytes += Math.max(0, copied);
                }
                if (resolvedEntry.originalFileName() != null
                        && resolvedEntry.sanitizedFileName() != null
                        && !resolvedEntry.originalFileName().equals(resolvedEntry.sanitizedFileName())) {
                    renamedFiles.putIfAbsent(resolvedEntry.originalFileName(), resolvedEntry.sanitizedFileName());
                }
                if (resolvedEntry.originalRelativePath() != null
                        && resolvedEntry.sanitizedRelativePath() != null
                        && !resolvedEntry.originalRelativePath().equals(resolvedEntry.sanitizedRelativePath())) {
                    renamedPaths.putIfAbsent(resolvedEntry.originalRelativePath(), resolvedEntry.sanitizedRelativePath());
                }
                files++;
            }
        }
        rewriteBeatmapReferences(destination, renamedFiles, renamedPaths);
        fixVideoFilenameFirstLetter(destination);
        return new ExtractionStats(files, bytes);
    }

    private String sanitize(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private ResolvedEntry resolveEntryPath(Path destination, String entryName) throws IOException {
        String normalizedName = entryName.replace('\\', '/');
        String[] segments = normalizedName.split("/");
        Path resolved = destination;
        String originalFileName = null;
        String sanitizedFileName = null;
        StringBuilder originalRelative = new StringBuilder();
        StringBuilder sanitizedRelative = new StringBuilder();
        for (String rawSegment : segments) {
            if (rawSegment.isEmpty() || ".".equals(rawSegment)) {
                continue;
            }
            if ("..".equals(rawSegment)) {
                throw new IOException("Se detectó una ruta inválida en el ZIP: " + entryName);
            }
            String sanitizedSegment = sanitizeSegment(rawSegment);
            resolved = resolved.resolve(sanitizedSegment);
            originalFileName = rawSegment;
            sanitizedFileName = sanitizedSegment;
            if (originalRelative.length() > 0) {
                originalRelative.append('/');
                sanitizedRelative.append('/');
            }
            originalRelative.append(rawSegment);
            sanitizedRelative.append(sanitizedSegment);
        }
        Path normalized = resolved.normalize();
        if (!normalized.startsWith(destination)) {
            throw new IOException("Se detectó una ruta inválida en el ZIP: " + entryName);
        }
        String originalPath = originalRelative.length() == 0 ? null : originalRelative.toString();
        String sanitizedPath = sanitizedRelative.length() == 0 ? null : sanitizedRelative.toString();
        return new ResolvedEntry(normalized, originalPath, sanitizedPath, originalFileName, sanitizedFileName);
    }

    private String sanitizeSegment(String segment) {
        String sanitized = sanitize(segment).trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isEmpty()) {
            sanitized = "_";
        }
        return sanitized;
    }

    private void rewriteBeatmapReferences(Path destination,
                                          Map<String, String> renamedFiles,
                                          Map<String, String> renamedPaths) throws IOException {
        if (renamedFiles.isEmpty() && renamedPaths.isEmpty()) {
            return;
        }
        try (var stream = Files.walk(destination)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".osu"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            String updated = replacePathReferences(content, renamedPaths);
                            updated = replaceFileReferences(updated, renamedFiles);
                            if (!updated.equals(content)) {
                                Files.writeString(path, updated, StandardCharsets.UTF_8);
                            }
                        } catch (CharacterCodingException ignored) {
                        } catch (IOException ignored) {}
                    });
        }
    }

    private String replacePathReferences(String content, Map<String, String> renamedPaths) {
        if (renamedPaths.isEmpty()) {
            return content;
        }
        String updated = content;
        for (Map.Entry<String, String> entry : renamedPaths.entrySet()) {
            String original = entry.getKey();
            String sanitized = entry.getValue();
            if (original == null || sanitized == null) {
                continue;
            }
            updated = replaceAllVariants(updated, original, sanitized);
        }
        return updated;
    }

    private void fixVideoFilenameFirstLetter(Path destination) throws IOException {
        try (var stream = Files.walk(destination)) {
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".osu"))
                    .forEach(path -> {
                        try {
                            List<String> references = extractVideoReferences(path);
                            for (String reference : references) {
                                fixVideoNameMismatch(destination, reference);
                            }
                        } catch (CharacterCodingException ignored) {
                        } catch (IOException ignored) {}
                    });
        }
    }

    private List<String> extractVideoReferences(Path osuFile) throws IOException {
        List<String> references = new ArrayList<>();
        boolean inEvents = false;
        try (BufferedReader reader = Files.newBufferedReader(osuFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    continue;
                }
                if ("[Events]".equalsIgnoreCase(trimmed)) {
                    inEvents = true;
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    if (inEvents) {
                        break;
                    }
                    continue;
                }
                if (!inEvents || !trimmed.toLowerCase(Locale.ROOT).startsWith("video")) {
                    continue;
                }
                String[] parts = trimmed.split(",", 3);
                if (parts.length < 3) {
                    continue;
                }
                String reference = parts[2].trim();
                if (reference.startsWith("\"") && reference.endsWith("\"") && reference.length() > 1) {
                    reference = reference.substring(1, reference.length() - 1);
                }
                if (!reference.isEmpty()) {
                    references.add(reference);
                }
            }
        }
        return references;
    }

    private void fixVideoNameMismatch(Path destination, String relativeReference) throws IOException {
        String normalized = relativeReference.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return;
        }
        Path expectedPath = destination.resolve(normalized);
        if (Files.exists(expectedPath)) {
            return;
        }
        int lastSlash = normalized.lastIndexOf('/');
        String dirPart = lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
        String filePart = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        if (filePart.length() < 2 || !hasVideoExtension(filePart)) {
            return;
        }
        Path parentDir = dirPart.isEmpty() ? destination : destination.resolve(dirPart);
        if (!Files.isDirectory(parentDir)) {
            return;
        }
        Path target = parentDir.resolve(filePart);
        List<Path> videoCandidates;
        try (var files = Files.list(parentDir)) {
            videoCandidates = files.filter(Files::isRegularFile)
                    .filter(path -> hasVideoExtension(path.getFileName().toString()))
                    .toList();
        }
        if (videoCandidates.isEmpty()) {
            return;
        }

        String expectedNorm = normalizeComparable(flattenReferenceName(filePart));
        Path normalizedMatch = videoCandidates.stream()
                .filter(path -> normalizeComparable(flattenReferenceName(path.getFileName().toString())).equals(expectedNorm))
                .findFirst()
                .orElse(null);
        if (normalizedMatch != null) {
            moveSilently(normalizedMatch, target);
            return;
        }

        Path fuzzyMatch = videoCandidates.stream()
                .filter(path -> isFirstLetterMismatch(path.getFileName().toString(), filePart))
                .findFirst()
                .orElse(null);
        if (fuzzyMatch != null) {
            moveSilently(fuzzyMatch, target);
            return;
        }

        if (videoCandidates.size() == 1) {
            moveSilently(videoCandidates.get(0), target);
        }
    }

    private void moveSilently(Path source, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(source, target);
        } catch (IOException ignored) {}
    }

    private String flattenReferenceName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private boolean isFirstLetterMismatch(String actual, String expected) {
        if (actual.length() < 2 || expected.length() < 2) {
            return false;
        }
        String actualTail = normalizeComparable(actual.substring(1));
        String expectedTail = normalizeComparable(expected.substring(1));
        if (!tailsRoughlyEqual(actualTail, expectedTail)) {
            return false;
        }
        String actualFirst = normalizeComparable(actual.substring(0, 1));
        String expectedFirst = normalizeComparable(expected.substring(0, 1));
        return !actualFirst.equalsIgnoreCase(expectedFirst);
    }

    private String normalizeComparable(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean tailsRoughlyEqual(String a, String b) {
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        int mismatches = 0;
        while (i < a.length() && j < b.length()) {
            char ca = Character.toLowerCase(a.charAt(i));
            char cb = Character.toLowerCase(b.charAt(j));
            if (ca == cb) {
                i++;
                j++;
                continue;
            }
            mismatches++;
            if (mismatches > 1) {
                return false;
            }
            if (a.length() > b.length()) {
                i++;
            } else if (b.length() > a.length()) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        if (i < a.length() || j < b.length()) {
            mismatches++;
        }
        return mismatches <= 1;
    }

    private boolean hasVideoExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(extension);
    }

        private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(
            Set.of(".mp4", ".avi", ".flv", ".mov", ".mkv", ".webm", ".wmv", ".mpg", ".mpeg"));

                private static final List<Charset> ZIP_CHARSET_CANDIDATES = List.of(
                    StandardCharsets.UTF_8,
                    Charset.forName("CP437"),
                    StandardCharsets.ISO_8859_1,
                    Charset.forName("windows-1250"),
                    Charset.forName("windows-1251"),
                    Charset.forName("windows-1252"),
                    Charset.forName("windows-1253"),
                    Charset.forName("windows-1254"),
                    Charset.forName("windows-1255"),
                    Charset.forName("windows-1256"),
                    Charset.forName("windows-1257"),
                    Charset.forName("windows-1258"),
                    Charset.forName("Shift_JIS"),
                    Charset.forName("GBK"),
                    Charset.forName("Big5"),
                    Charset.forName("EUC-JP"),
                    Charset.forName("EUC-KR")
                );

    private String replaceFileReferences(String content, Map<String, String> renamedFiles) {
        if (renamedFiles.isEmpty()) {
            return content;
        }
        String updated = content;
        for (Map.Entry<String, String> entry : renamedFiles.entrySet()) {
            String original = entry.getKey();
            String sanitized = entry.getValue();
            if (original == null || sanitized == null) {
                continue;
            }
            updated = updated.replace(original, sanitized);
        }
        return updated;
    }

    private String replaceAllVariants(String content, String original, String replacement) {
        String updated = content.replace(original, replacement);
        String originalBack = original.replace('/', '\\');
        String replacementBack = replacement.replace('/', '\\');
        if (!originalBack.equals(original) || !replacementBack.equals(replacement)) {
            updated = updated.replace(originalBack, replacementBack);
        }
        return updated;
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walk(directory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private void resetDestinationFolder(Path destination) throws IOException {
        deleteDirectoryQuietly(destination);
        Files.createDirectories(destination);
    }

    private record ExtractionStats(int filesExtracted, long bytesWritten) {}

    private record ResolvedEntry(Path path,
                                 String originalRelativePath,
                                 String sanitizedRelativePath,
                                 String originalFileName,
                                 String sanitizedFileName) {}
}
