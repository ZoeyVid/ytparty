package com.mcyt.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class CommandParty implements CommandExecutor {
    private final PartyManager partyManager;
    private final NetworkHandler networkHandler;

    public CommandParty(PartyManager partyManager, NetworkHandler networkHandler) {
        this.partyManager = partyManager;
        this.networkHandler = networkHandler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /party <create|join <player>|leave>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create": {
                Party p = partyManager.createParty(player.getUniqueId());
                if (p != null) {
                    player.sendMessage(ChatColor.GREEN + "Party created!");
                    networkHandler.syncParty(p);
                } else {
                    player.sendMessage(ChatColor.RED + "You are already in a party.");
                }
                break;
            }
            case "join": {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /party join <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
                if (partyManager.joinParty(player.getUniqueId(), target.getUniqueId())) {
                    player.sendMessage(ChatColor.GREEN + "Joined party!");
                    networkHandler.syncParty(partyManager.getPartyForPlayer(player.getUniqueId()));
                } else {
                    player.sendMessage(ChatColor.RED + "Could not join the party. Are you already in one?");
                }
                break;
            }
            case "leave": {
                Party p = partyManager.getPartyForPlayer(player.getUniqueId());
                if (p != null) {
                    partyManager.leaveParty(player.getUniqueId());
                    player.sendMessage(ChatColor.YELLOW + "You left the party.");
                    networkHandler.syncParty(p);
                } else {
                    player.sendMessage(ChatColor.RED + "You are not in a party.");
                }
                break;
            }
            default:
                player.sendMessage(ChatColor.RED + "Unknown command.");
        }
        return true;
    }
}
