package com.mcyt.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NetworkHandler implements PluginMessageListener {
    private final McytPlugin plugin;
    private final PartyManager partyManager;
    private final Gson gson = new Gson();

    public NetworkHandler(McytPlugin plugin, PartyManager partyManager) {
        this.plugin = plugin;
        this.partyManager = partyManager;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("mcyt:action")) return;
        
        String jsonStr = new String(message, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
        String action = json.get("action").getAsString();

        Party party = partyManager.getPartyForPlayer(player.getUniqueId());
        if (party == null) return; // Only process if in a party

        switch (action) {
            case "pause": {
                boolean targetPause = json.get("paused").getAsBoolean();
                party.setPaused(targetPause);
                syncParty(party);
                break;
            }
            case "play_track": { // direct URL add or play
                String url = json.get("url").getAsString();
                party.addTrack(url);
                syncParty(party);
                break;
            }
            case "reorder": {
                JsonArray arr = json.getAsJsonArray("playlist");
                List<String> newPlaylist = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    newPlaylist.add(arr.get(i).getAsString());
                }
                party.setPlaylist(newPlaylist);
                syncParty(party);
                break;
            }
        }
    }

    public void syncParty(Party party) {
        JsonObject state = new JsonObject();
        state.addProperty("paused", party.isPaused());
        state.addProperty("currentIndex", party.getCurrentTrackIndex());
        state.addProperty("leader", party.getLeader().toString());
        
        JsonArray playlistArray = new JsonArray();
        for (String url : party.getPlaylist()) {
            playlistArray.add(url);
        }
        state.add("playlist", playlistArray);

        String jsonOut = gson.toJson(state);
        byte[] bytes = jsonOut.getBytes(StandardCharsets.UTF_8);

        for (UUID memberUUID : party.getMembers()) {
            Player p = Bukkit.getPlayer(memberUUID);
            if (p != null && p.isOnline()) {
                p.sendPluginMessage(plugin, "mcyt:sync", bytes);
            }
        }
    }
}
