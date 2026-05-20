package com.mcyt.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public class McytPlugin extends JavaPlugin {
    
    private PartyManager partyManager;
    private NetworkHandler networkHandler;

    @Override
    public void onEnable() {
        this.partyManager = new PartyManager();
        this.networkHandler = new NetworkHandler(this, partyManager);
        
        getCommand("party").setExecutor(new CommandParty(this.partyManager, this.networkHandler));
        
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "mcyt:sync");
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "mcyt:action", this.networkHandler);
        
        getLogger().info("McytPlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("McytPlugin has been disabled!");
    }
}
