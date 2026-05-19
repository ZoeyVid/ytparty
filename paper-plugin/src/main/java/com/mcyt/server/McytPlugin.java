package com.mcyt.server;

import org.bukkit.plugin.java.JavaPlugin;

public class McytPlugin extends JavaPlugin {

    private PartyManager partyManager;
    private NetworkHandler networkHandler;

    @Override
    public void onEnable() {
        this.partyManager = new PartyManager(this);
        this.networkHandler = new NetworkHandler(this, this.partyManager);

        // Register custom payload channel
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "mcyt:sync");
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "mcyt:sync", this.networkHandler);
        
        this.getCommand("mcyt").setExecutor(new McytCommand(this.partyManager));

        getLogger().info("McytPlugin enabled!");
    }

    @Override
    public void onDisable() {
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getLogger().info("McytPlugin disabled!");
    }
}
