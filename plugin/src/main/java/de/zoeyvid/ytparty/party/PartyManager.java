package de.zoeyvid.ytparty.party;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PartyManager {
    public record LeaveResult(Party party, boolean disbanded) {}

    private final Map<String, Party> byId = new HashMap<>();
    private final Map<UUID, String> playerToParty = new HashMap<>();
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static final String IDCHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private String newId() {
        String id;
        do { StringBuilder s = new StringBuilder(8); for (int i = 0; i < 8; i++) s.append(IDCHARS.charAt(RNG.nextInt(IDCHARS.length()))); id = s.toString(); } while (byId.containsKey(id));
        return id;
    }
    private boolean defaultPublic = false;
    private PermissionLevel defaultPublicLevel = PermissionLevel.LISTEN;

    public void setDefaults(boolean pub, PermissionLevel level) { defaultPublic = pub; defaultPublicLevel = level; }

    public Party of(UUID player) { String id = playerToParty.get(player); return id != null ? byId.get(id) : null; }
    public Party get(String id) { return byId.get(id); }

    public Party create(Player host) {
        String id = newId();
        Party party = new Party(id, host.getUniqueId(), defaultPublic, defaultPublicLevel);
        byId.put(id, party);
        playerToParty.put(host.getUniqueId(), id);
        return party;
    }

    public Party join(Player player, String id) {
        Party party = byId.get(id);
        if (party == null) return null;
        UUID u = player.getUniqueId();
        if (party.members.containsKey(u)) return party;
        PermissionLevel level;
        if (party.invites.containsKey(u)) level = party.invites.remove(u);
        else if (party.isPublic) level = party.publicJoinLevel;
        else return null;
        party.members.put(u, level);
        playerToParty.put(u, id);
        return party;
    }

    public LeaveResult leave(UUID player) {
        String id = playerToParty.remove(player);
        if (id == null) return null;
        Party party = byId.get(id);
        if (party == null) return null;
        party.members.remove(player);
        return finishIfUnmanaged(party);
    }

    public LeaveResult setLevel(Party party, UUID target, PermissionLevel level) {
        if (!party.members.containsKey(target)) return null;
        party.members.put(target, level);
        return finishIfUnmanaged(party);
    }

    private LeaveResult finishIfUnmanaged(Party party) {
        boolean disbanded = party.members.isEmpty() || !party.hasManager();
        if (disbanded) {
            for (UUID m : party.members.keySet()) playerToParty.remove(m);
            byId.remove(party.id);
        }
        return new LeaveResult(party, disbanded);
    }
}
