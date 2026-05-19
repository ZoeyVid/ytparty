package com.mcyt.server;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class McytCommand implements CommandExecutor {
    private final PartyManager partyManager;

    public McytCommand(PartyManager partyManager) {
        this.partyManager = partyManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("Usage: /mcyt party <create|join|leave> [id]");
            return true;
        }

        if (args[0].equalsIgnoreCase("party")) {
            if (args.length == 1) {
                player.sendMessage("Usage: /mcyt party <create|join|leave> [id]");
                return true;
            }

            String action = args[1];

            if (action.equalsIgnoreCase("leave")) {
                partyManager.leaveParty(player);
                player.sendMessage("You left the party.");
                return true;
            }

            if (args.length < 3) {
                player.sendMessage("Please specify a party ID.");
                return true;
            }

            String id = args[2];

            if (action.equalsIgnoreCase("create")) {
                Party p = partyManager.createParty(id, player);
                if (p != null) {
                    player.sendMessage("Created and joined party: " + id);
                } else {
                    player.sendMessage("Party already exists!");
                }
            } else if (action.equalsIgnoreCase("join")) {
                boolean joined = partyManager.joinParty(id, player);
                if (joined) {
                    player.sendMessage("Joined party: " + id);
                } else {
                    player.sendMessage("Party does not exist!");
                }
            } else {
                player.sendMessage("Unknown action.");
            }
            return true;
        }

        return false;
    }
}
