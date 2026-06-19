package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.net.ChannelBridge;
import de.zoeyvid.ytparty.net.ServerProtocol;
import de.zoeyvid.ytparty.party.PartyManager;
import de.zoeyvid.ytparty.party.PermissionLevel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class YtPartyPlugin extends JavaPlugin implements Listener {
    private final PartyManager manager = new PartyManager();
    private ChannelBridge bridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager.setDefaults(getConfig().getBoolean("default-public", false),
            PermissionLevel.fromName(getConfig().getString("public-join-level", "listen")));

        bridge = new ChannelBridge(this, manager);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ServerProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, ServerProtocol.CHANNEL, bridge);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bridge.afterLeave(manager.leave(event.getPlayer().getUniqueId()));
    }
}
