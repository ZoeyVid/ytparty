package com.mcyt.server;

import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PartyManager {
    private final McytPlugin plugin;
    private final Map<String, Party> parties;
    private final Map<Player, Party> playerParties;

    public PartyManager(McytPlugin plugin) {
        this.plugin = plugin;
        this.parties = new HashMap<>();
        this.playerParties = new HashMap<>();
    }

    public Party createParty(String id, Player creator) {
        if (parties.containsKey(id)) {
            return null; // Already exists
        }
        Party party = new Party(id);
        parties.put(id, party);
        joinParty(party, creator);
        return party;
    }

    public boolean joinParty(String id, Player player) {
        Party party = parties.get(id);
        if (party != null) {
            return joinParty(party, player);
        }
        return false;
    }

    private boolean joinParty(Party party, Player player) {
        leaveParty(player);
        party.addMember(player);
        playerParties.put(player, party);
        // Send current state to the joining player
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (party.getLastStateJson() != null && !party.getLastStateJson().equals("{}")) {
                NetworkHandler.sendStateToPlayer(plugin, player, party.getLastStateJson());
            }
        }, 10L);
        return true;
    }

    public void leaveParty(Player player) {
        Party party = playerParties.remove(player);
        if (party != null) {
            party.removeMember(player);
            if (party.getMembers().isEmpty()) {
                parties.remove(party.getId());
            }
        }
    }

    public Optional<Party> getParty(Player player) {
        return Optional.ofNullable(playerParties.get(player));
    }
}
