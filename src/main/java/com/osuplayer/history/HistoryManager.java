package com.osuplayer.history;

import java.util.ArrayList;
import java.util.List;

public class HistoryManager {
    private final List<String> history = new ArrayList<>();
    private int currentIndex = -1;

    
    public void addSong(String songName) {
        if (songName == null) return;
        history.remove(songName);
        history.add(songName);
        currentIndex = history.size() - 1;
    }

    
    public boolean hasPrevious() {
        return currentIndex > 0;
    }

    
    public boolean hasNext() {
        return currentIndex >= 0 && currentIndex < history.size() - 1;
    }

    
    public String getPrevious() {
        if (!hasPrevious()) return null;
        currentIndex--;
        return history.get(currentIndex);
    }

    
    public String getNext() {
        if (!hasNext()) return null;
        currentIndex++;
        return history.get(currentIndex);
    }

    
    public String getCurrent() {
        if (currentIndex < 0 || currentIndex >= history.size()) return null;
        return history.get(currentIndex);
    }

    
    public int getIndex() {
        return currentIndex;
    }

    
    public void setIndex(int index) {
        if (index >= 0 && index < history.size()) {
            currentIndex = index;
        }
    }

    
    public void setHistory(List<String> newHistory, int index) {
        history.clear();
        if (newHistory != null) {
            history.addAll(newHistory);
        }
        if (index >= 0 && index < history.size()) {
            currentIndex = index;
        } else {
            currentIndex = history.isEmpty() ? -1 : 0;
        }
    }

    
    public List<String> getHistory() {
        return new ArrayList<>(history);
    }

    
    public void clear() {
        history.clear();
        currentIndex = -1;
    }

    
    public boolean isEmpty() {
        return history.isEmpty();
    }
}
