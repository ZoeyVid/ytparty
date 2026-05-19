package com.mcyt.server;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.nio.charset.StandardCharsets;

public class NetworkHandler implements PluginMessageListener {
    private final McytPlugin plugin;
    private final PartyManager partyManager;

    public NetworkHandler(McytPlugin plugin, PartyManager partyManager) {
        this.plugin = plugin;
        this.partyManager = partyManager;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("mcyt:sync")) {
            return;
        }

        String jsonPayload = new String(message, StandardCharsets.UTF_8);

        // Basic routing based on payload string
        if (jsonPayload.startsWith("JOIN:")) {
            String partyId = jsonPayload.substring(5);
            partyManager.joinParty(partyId, player);
            player.sendMessage("Joined party: " + partyId);
            return;
        } else if (jsonPayload.startsWith("CREATE:")) {
            String partyId = jsonPayload.substring(7);
            Party p = partyManager.createParty(partyId, player);
            if (p != null) {
                player.sendMessage("Created party: " + partyId);
            } else {
                player.sendMessage("Party already exists!");
            }
            return;
        } else if (jsonPayload.equals("LEAVE")) {
            partyManager.leaveParty(player);
            player.sendMessage("Left party.");
            return;
        }

        // Otherwise, it's a state update
        partyManager.getParty(player).ifPresent(party -> {
            party.setLastStateJson(jsonPayload);
            // Broadcast to other members
            for (Player member : party.getMembers()) {
                if (!member.equals(player)) {
                    sendStateToPlayer(plugin, member, jsonPayload);
                }
            }
        });
    }

    public static void sendStateToPlayer(McytPlugin plugin, Player player, String jsonPayload) {
        player.sendPluginMessage(plugin, "mcyt:sync", jsonPayload.getBytes(StandardCharsets.UTF_8));
    }
}
