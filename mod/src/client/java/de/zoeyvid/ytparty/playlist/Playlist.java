package de.zoeyvid.ytparty.playlist;

import java.util.ArrayList;
import java.util.List;

public final class Playlist {
    private final List<Track> tracks = new ArrayList<>();
    private int version;

    public synchronized List<Track> view() { return List.copyOf(tracks); }
    public synchronized int size() { return tracks.size(); }
    public synchronized int version() { return version; }
    public synchronized Track get(int i) { return i >= 0 && i < tracks.size() ? tracks.get(i) : null; }
    public synchronized void add(Track t) { tracks.add(t); version++; }
    public synchronized void remove(int i) { if (i >= 0 && i < tracks.size()) { tracks.remove(i); version++; } }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size()) return;
        tracks.add(to, tracks.remove(from));
        version++;
    }

    public synchronized void replaceAll(List<Track> next) {
        if (tracks.equals(next)) return;
        tracks.clear();
        tracks.addAll(next);
        version++;
    }
}
