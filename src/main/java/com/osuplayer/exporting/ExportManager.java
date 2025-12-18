package com.osuplayer.exporting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.osuplayer.playback.MusicManager;

public class ExportManager {

    private static final Logger LOGGER = Logger.getLogger(ExportManager.class.getName());
    private final MusicManager musicManager;

    public ExportManager(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    public void exportSong(String songName) {
        String songPath = musicManager.getSongPath(songName);
        if (songPath == null) return;

        try {
            File exportDir = new File("Export");
            if (!exportDir.exists()) exportDir.mkdirs();

            String extension = "";
            int dotIndex = songPath.lastIndexOf('.');
            if (dotIndex != -1) {
                extension = songPath.substring(dotIndex);
            }

            String safeName = songName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File exportFile = new File(exportDir, safeName + extension);

            Files.copy(new File(songPath).toPath(), exportFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("Canción exportada a: " + exportFile.getAbsolutePath());

        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Error exportando canción: " + songName, ex);
        }
    }

    public void exportPlaylist(String playlistName, Map<String, List<String>> playlists) {
        if (playlistName == null) return;
        List<String> songs = playlists.get(playlistName);
        if (songs == null || songs.isEmpty()) return;

        try {
            File exportDir = new File("Export");
            if (!exportDir.exists()) exportDir.mkdirs();

            for (String song : songs) {
                String songPath = musicManager.getSongPath(song);
                if (songPath != null) {
                    File sourceFile = new File(songPath);

                    String extension = "";
                    int dotIndex = songPath.lastIndexOf('.');
                    if (dotIndex != -1) {
                        extension = songPath.substring(dotIndex);
                    }

                    String safeName = song.replaceAll("[\\\\/:*?\"<>|]", "_");
                    File destFile = new File(exportDir, safeName + extension);

                    Files.copy(sourceFile.toPath(), destFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }

            LOGGER.info("Playlist exportada a carpeta: " + exportDir.getAbsolutePath());

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error exportando playlist: " + playlistName, e);
        }
    }
}
