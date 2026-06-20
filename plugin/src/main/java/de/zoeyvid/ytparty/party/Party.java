package de.zoeyvid.ytparty.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Party {
    public record TrackRef(int id, String uri, String title, String requester) {}
    public record MgrReport(long pos, long at) {}

    public final String id;
    public final Map<UUID, PermissionLevel> members = new LinkedHashMap<>();
    public final Map<UUID, PermissionLevel> invites = new HashMap<>();
    public final List<TrackRef> tracks = new ArrayList<>();
    public final Map<UUID, MgrReport> mgrPos = new HashMap<>();
    public boolean isPublic;
    public PermissionLevel publicJoinLevel;
    public int currentIndex = -1;
    public boolean paused = false;
    public boolean autoRemovePlayed = true;
    public byte sbFlags = 0x0F;
    public boolean repeatOne = false;
    public int nextTrackId = 1;
    public int generation = 0;

    public Party(String id, UUID host, boolean isPublic, PermissionLevel publicJoinLevel) {
        this.id = id;
        this.isPublic = isPublic;
        this.publicJoinLevel = publicJoinLevel;
        members.put(host, PermissionLevel.MANAGE);
    }

    public PermissionLevel level(UUID u) { return members.getOrDefault(u, PermissionLevel.LISTEN); }
    public boolean canManage(UUID u) { return level(u).canManage(); }
    public boolean canInvite(UUID u) { return level(u).canInvite(); }

    public int curTrackId() { return currentIndex >= 0 && currentIndex < tracks.size() ? tracks.get(currentIndex).id() : 0; }

    public int indexOf(int id) {
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id() == id) return i;
        return -1;
    }

    public boolean hasManager() {
        for (PermissionLevel l : members.values()) if (l.canManage()) return true;
        return false;
    }

    public void move(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size()) return;
        tracks.add(to, tracks.remove(from));
    }
}
