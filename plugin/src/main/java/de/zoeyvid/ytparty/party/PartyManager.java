package de.zoeyvid.ytparty.party;

import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PartyManager {
    public static final class LeaveResult {
        private final Party party;
        private final boolean disbanded;
        public LeaveResult(Party party, boolean disbanded) { this.party = party; this.disbanded = disbanded; }
        public Party party() { return party; }
        public boolean disbanded() { return disbanded; }
    }

    private final Map<String, Party> byId = new HashMap<>();
    private final Map<UUID, String> playerToParty = new HashMap<>();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String IDCHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private String newId() {
        String id;
        do { StringBuilder s = new StringBuilder(8); for (int i = 0; i < 8; i++) s.append(IDCHARS.charAt(RNG.nextInt(IDCHARS.length()))); id = s.toString(); } while (byId.containsKey(id));
        return id;
    }

    public Party of(UUID player) { String id = playerToParty.get(player); return id != null ? byId.get(id) : null; }
    public Party get(String id) { return byId.get(id); }
    public List<Party> publicParties() { List<Party> out = new ArrayList<>(); for (Party p : byId.values()) if (p.isPublic) out.add(p); out.sort((a, b) -> Integer.compare(b.members.size(), a.members.size())); return out; }
    public void forgetInvites(UUID u) { for (Party p : byId.values()) p.invites.remove(u); }

    public Party create(Player host) {
        String id = newId();
        Party party = new Party(id, host.getUniqueId());
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
