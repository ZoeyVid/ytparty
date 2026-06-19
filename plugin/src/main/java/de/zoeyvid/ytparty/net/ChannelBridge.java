package de.zoeyvid.ytparty.net;

import de.zoeyvid.ytparty.party.Party;
import de.zoeyvid.ytparty.party.PartyManager;
import de.zoeyvid.ytparty.party.PermissionLevel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ChannelBridge implements PluginMessageListener {
    private final Plugin plugin;
    private final PartyManager manager;

    public ChannelBridge(Plugin plugin, PartyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void broadcast(Party party) {
        for (UUID member : party.members.keySet()) {
            Player p = Bukkit.getPlayer(member);
            if (p != null) send(p, ServerProtocol.state(party, member));
        }
    }

    public void sendLeft(Player player) { send(player, ServerProtocol.left()); }

    public void afterLeave(PartyManager.LeaveResult r) {
        if (r == null) return;
        if (r.disbanded()) {
            for (UUID m : r.party().members.keySet()) { Player pl = Bukkit.getPlayer(m); if (pl != null) send(pl, ServerProtocol.left()); }
        } else broadcast(r.party());
    }

    public void create(Player player) {
        afterLeave(manager.leave(player.getUniqueId()));
        broadcast(manager.create(player));
    }

    public void join(Player player, String id) {
        Party target = manager.get(id);
        UUID u = player.getUniqueId();
        if (target == null || !(target.members.containsKey(u) || target.invites.containsKey(u) || target.isPublic)) {
            send(player, ServerProtocol.message("Cannot join " + id));
            return;
        }
        if (target.members.containsKey(u)) { broadcast(target); return; }
        afterLeave(manager.leave(u));
        Party p = manager.join(player, id);
        if (p != null) broadcast(p);
    }

    public void leave(Player player) {
        PartyManager.LeaveResult r = manager.leave(player.getUniqueId());
        sendLeft(player);
        afterLeave(r);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(ServerProtocol.CHANNEL) || message.length == 0) return;
        try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = d.readByte();
            switch (op) {
                case ServerProtocol.C2S_CREATE -> create(player);
                case ServerProtocol.C2S_JOIN -> join(player, d.readUTF());
                case ServerProtocol.C2S_LEAVE -> leave(player);
                case ServerProtocol.C2S_INVITE -> handleInvite(player, d.readUTF(), d.readByte());
                case ServerProtocol.C2S_SET_LEVEL -> handleSetLevel(player, d.readUTF(), d.readByte());
                case ServerProtocol.C2S_SET_PUBLIC -> handleSetPublic(player, d.readBoolean(), d.readByte());
                default -> handleControlOp(player, op, d);
            }
        } catch (IOException ignored) {}
    }

    private void handleInvite(Player player, String name, byte levelId) {
        Party p = manager.of(player.getUniqueId());
        if (p == null || !p.canInvite(player.getUniqueId())) return;
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) { send(player, ServerProtocol.message("Player not online: " + name)); return; }
        PermissionLevel granted = PermissionLevel.fromId(levelId).cappedTo(p.level(player.getUniqueId()));
        p.invites.put(target.getUniqueId(), granted);
        send(target, ServerProtocol.invited(player.getName(), p.id, granted));
    }

    private void handleSetLevel(Player player, String name, byte levelId) {
        Party p = manager.of(player.getUniqueId());
        if (p == null || !p.canManage(player.getUniqueId())) return;
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) return;
        afterLeave(manager.setLevel(p, target.getUniqueId(), PermissionLevel.fromId(levelId)));
    }

    private void handleSetPublic(Player player, boolean isPublic, byte levelId) {
        Party p = manager.of(player.getUniqueId());
        if (p == null || !p.canManage(player.getUniqueId())) return;
        p.isPublic = isPublic;
        p.publicJoinLevel = PermissionLevel.fromId(levelId);
        broadcast(p);
    }

    private void handleControlOp(Player player, byte op, DataInputStream d) throws IOException {
        Party p = manager.of(player.getUniqueId());
        if (p == null) return;
        if (!p.canManage(player.getUniqueId())) { send(player, ServerProtocol.state(p, player.getUniqueId())); return; }
        switch (op) {
            case ServerProtocol.C2S_ADD -> {
                String uri = d.readUTF();
                String title = cap(d.readUTF(), 200);
                if (uri.isEmpty() || uri.length() > 1000 || p.tracks.size() >= 500) return;
                p.tracks.add(new Party.TrackRef(uri, title, player.getName()));
                if (p.currentIndex < 0) p.currentIndex = 0;
            }
            case ServerProtocol.C2S_REMOVE -> {
                int i = d.readInt();
                if (i >= 0 && i < p.tracks.size()) {
                    p.tracks.remove(i);
                    if (i < p.currentIndex) p.currentIndex--;
                    if (p.currentIndex >= p.tracks.size()) p.currentIndex = p.tracks.size() - 1;
                }
            }
            case ServerProtocol.C2S_MOVE -> {
                int from = d.readInt(), to = d.readInt();
                p.move(from, to);
                if (from == p.currentIndex) p.currentIndex = to;
                else if (from < p.currentIndex && to >= p.currentIndex) p.currentIndex--;
                else if (from > p.currentIndex && to <= p.currentIndex) p.currentIndex++;
            }
            case ServerProtocol.C2S_SET_INDEX -> {
                int i = d.readInt();
                if (p.autoRemovePlayed && i == p.currentIndex + 1 && p.currentIndex >= 0 && p.currentIndex < p.tracks.size()) {
                    int cur = p.currentIndex;
                    p.tracks.remove(cur);
                    p.currentIndex = Math.min(cur, p.tracks.size() - 1);
                } else {
                    p.currentIndex = i < -1 ? -1 : Math.min(i, p.tracks.size() - 1);
                }
                p.paused = false;
                p.mgrPos.clear();
            }
            case ServerProtocol.C2S_SET_PAUSED -> p.paused = d.readBoolean();
            case ServerProtocol.C2S_SET_AUTOREMOVE -> p.autoRemovePlayed = d.readBoolean();
            case ServerProtocol.C2S_SET_SPONSORBLOCK -> p.sbFlags = (byte) (d.readByte() & 0x0F);
            case ServerProtocol.C2S_SET_REPEAT -> p.repeatOne = d.readBoolean();
            case ServerProtocol.C2S_REPORT_POSITION -> {
                long ms = d.readLong();
                p.mgrPos.put(player.getUniqueId(), new Party.MgrReport(ms, System.currentTimeMillis()));
                driftSeek(p);
                return;
            }
            case ServerProtocol.C2S_SET_POSITION -> {
                long ms = d.readLong();
                p.mgrPos.clear();
                for (UUID m : p.members.keySet()) { Player pl = Bukkit.getPlayer(m); if (pl != null) send(pl, ServerProtocol.seek(ms)); }
                return;
            }
            default -> { return; }
        }
        broadcast(p);
    }

    private void driftSeek(Party p) {
        long now = System.currentTimeMillis();
        p.mgrPos.entrySet().removeIf(e -> !p.canManage(e.getKey()) || now - e.getValue().at() > 15000);
        if (p.mgrPos.isEmpty()) return;
        List<Long> vals = new ArrayList<>();
        for (Party.MgrReport r : p.mgrPos.values()) vals.add(p.paused ? r.pos() : r.pos() + (now - r.at()));
        Collections.sort(vals);
        int n = vals.size();
        long med = n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2;
        for (UUID m : p.members.keySet()) { Player pl = Bukkit.getPlayer(m); if (pl != null) send(pl, ServerProtocol.seek(med)); }
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private void send(Player player, byte[] data) { player.sendPluginMessage(plugin, ServerProtocol.CHANNEL, data); }
}
