package com.mcyt.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PartyManager {
    // Leader UUID -> Party
    private final Map<UUID, Party> parties = new HashMap<>();
    // Player UUID -> Party Leader UUID
    private final Map<UUID, UUID> playerPartyMap = new HashMap<>();

    public Party createParty(UUID leader) {
        if (playerPartyMap.containsKey(leader)) return null; // Already in a party
        Party party = new Party(leader);
        parties.put(leader, party);
        playerPartyMap.put(leader, leader);
        return party;
    }

    public boolean joinParty(UUID player, UUID leader) {
        if (playerPartyMap.containsKey(player)) return false; // Already in a party
        Party party = parties.get(leader);
        if (party != null) {
            party.addMember(player);
            playerPartyMap.put(player, leader);
            return true;
        }
        return false;
    }

    public void leaveParty(UUID player) {
        UUID leader = playerPartyMap.remove(player);
        if (leader != null) {
            Party party = parties.get(leader);
            if (party != null) {
                party.removeMember(player);
                if (party.getMembers().isEmpty() || leader.equals(player)) {
                    parties.remove(leader);
                    // Disband party, remove all members
                    for (UUID member : party.getMembers()) {
                        playerPartyMap.remove(member);
                    }
                }
            }
        }
    }

    public Party getPartyForPlayer(UUID player) {
        UUID leader = playerPartyMap.get(player);
        if (leader != null) {
            return parties.get(leader);
        }
        return null;
    }
}
