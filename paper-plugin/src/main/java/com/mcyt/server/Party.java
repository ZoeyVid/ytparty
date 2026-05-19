package com.mcyt.server;

import org.bukkit.entity.Player;
import java.util.HashSet;
import java.util.Set;

public class Party {
    private final String id;
    private final Set<Player> members;
    private String lastStateJson;

    public Party(String id) {
        this.id = id;
        this.members = new HashSet<>();
        this.lastStateJson = "{}";
    }

    public String getId() {
        return id;
    }

    public Set<Player> getMembers() {
        return members;
    }

    public void addMember(Player player) {
        members.add(player);
    }

    public void removeMember(Player player) {
        members.remove(player);
    }

    public String getLastStateJson() {
        return lastStateJson;
    }

    public void setLastStateJson(String lastStateJson) {
        this.lastStateJson = lastStateJson;
    }
}
