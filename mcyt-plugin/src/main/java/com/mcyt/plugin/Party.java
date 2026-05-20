package com.mcyt.plugin;

import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Party {
    private final UUID leader;
    private final List<UUID> members = new ArrayList<>();
    private final List<String> playlist = new ArrayList<>();
    private boolean isPaused = false;
    private int currentTrackIndex = 0;
    private long currentPosition = 0; // ms

    public Party(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID getLeader() { return leader; }
    public List<UUID> getMembers() { return members; }
    
    public void addMember(UUID uuid) {
        if (!members.contains(uuid)) members.add(uuid);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public List<String> getPlaylist() { return playlist; }
    public void addTrack(String url) { playlist.add(url); }
    public void setPlaylist(List<String> newPlaylist) {
        this.playlist.clear();
        this.playlist.addAll(newPlaylist);
    }

    public boolean isPaused() { return isPaused; }
    public void setPaused(boolean paused) { isPaused = paused; }

    public int getCurrentTrackIndex() { return currentTrackIndex; }
    public void setCurrentTrackIndex(int currentTrackIndex) { this.currentTrackIndex = currentTrackIndex; }
    
    public long getCurrentPosition() { return currentPosition; }
    public void setCurrentPosition(long currentPosition) { this.currentPosition = currentPosition; }
}
