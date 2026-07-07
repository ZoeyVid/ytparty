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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChannelBridge implements PluginMessageListener {
    private static final double MSG_BURST = 16, MSG_RATE = 4;

    private final Plugin plugin;
    private final PartyManager manager;
    private final Map<UUID, Bucket> buckets = new HashMap<>();

    private static final class Bucket {
        double tokens;
        long last;
        Bucket(double tokens, long last) { this.tokens = tokens; this.last = last; }
    }

    public ChannelBridge(Plugin plugin, PartyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void broadcast(Party party) {
        ServerProtocol.StateTemplate t = ServerProtocol.stateTemplate(party);
        for (UUID member : party.members.keySet()) {
            Player p = Bukkit.getPlayer(member);
            if (p == null) continue;
            byte[] msg = t.bytes().clone();
            msg[t.levelOffset()] = party.level(member).id();
            send(p, msg);
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

    public void leave(Player player, String target) {
        if (!target.isEmpty()) {
            Party p = manager.of(player.getUniqueId());
            if (p == null || !p.canManage(player.getUniqueId())) return;
            Player t = Bukkit.getPlayerExact(target);
            if (t == null || t.getUniqueId().equals(player.getUniqueId()) || !p.members.containsKey(t.getUniqueId())) return;
            sendLeft(t);
            afterLeave(manager.leave(t.getUniqueId()));
            return;
        }
        PartyManager.LeaveResult r = manager.leave(player.getUniqueId());
        sendLeft(player);
        afterLeave(r);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(ServerProtocol.CHANNEL) || message.length == 0) return;
        synchronized (manager) {
            if (!allow(player.getUniqueId())) return;
            try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(message))) {
                byte op = d.readByte();
                switch (op) {
                    case ServerProtocol.C2S_CREATE: create(player); break;
                    case ServerProtocol.C2S_JOIN: join(player, d.readUTF()); break;
                    case ServerProtocol.C2S_LEAVE: leave(player, d.available() > 0 ? d.readUTF() : ""); break;
                    case ServerProtocol.C2S_INVITE: handleInvite(player, d.readUTF(), d.readByte()); break;
                    case ServerProtocol.C2S_SET_LEVEL: handleSetLevel(player, d.readUTF(), d.readByte()); break;
                    case ServerProtocol.C2S_SET_PUBLIC: handleSetPublic(player, d.readBoolean(), d.readByte()); break;
                    case ServerProtocol.C2S_LIST_PUBLIC: send(player, ServerProtocol.publicList(manager.publicParties())); break;
                    default: handleControlOp(player, op, d);
                }
            } catch (IOException ignored) {}
        }
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
        int oldCur = p.curTrackId();
        int genBefore = p.generation;
        switch (op) {
            case ServerProtocol.C2S_ADD: {
                String uri = d.readUTF();
                String title = cap(d.readUTF(), 200);
                if (uri.isEmpty() || uri.length() > 1000 || p.tracks.size() >= 500) return;
                p.tracks.add(new Party.TrackRef(p.nextTrackId++, uri, title, player.getName()));
                if (p.currentIndex < 0) p.currentIndex = 0;
                break;
            }
            case ServerProtocol.C2S_REMOVE: {
                int i = p.indexOf(d.readInt());
                if (i >= 0) {
                    p.tracks.remove(i);
                    if (i < p.currentIndex) p.currentIndex--;
                    if (p.currentIndex >= p.tracks.size()) p.currentIndex = p.tracks.size() - 1;
                }
                break;
            }
            case ServerProtocol.C2S_MOVE: {
                int from = p.indexOf(d.readInt()), to = d.readInt();
                if (from >= 0) {
                    to = Math.max(0, Math.min(to, p.tracks.size() - 1));
                    p.move(from, to);
                    if (from == p.currentIndex) p.currentIndex = to;
                    else if (from < p.currentIndex && to >= p.currentIndex) p.currentIndex--;
                    else if (from > p.currentIndex && to <= p.currentIndex) p.currentIndex++;
                }
                break;
            }
            case ServerProtocol.C2S_SET_TRACK: {
                int i = p.indexOf(d.readInt());
                if (i < 0) return;
                p.currentIndex = i;
                p.paused = false;
                break;
            }
            case ServerProtocol.C2S_TRACK_ENDED: {
                int gen = d.readInt();
                if (gen != p.generation || p.currentIndex < 0) return;
                if (p.repeatOne || (!p.autoRemovePlayed && p.tracks.size() == 1)) p.generation++;
                else if (p.autoRemovePlayed && p.currentIndex < p.tracks.size()) {
                    int cur = p.currentIndex;
                    p.tracks.remove(cur);
                    p.currentIndex = Math.min(cur, p.tracks.size() - 1);
                } else if (!p.tracks.isEmpty()) p.currentIndex = (p.currentIndex + 1) % p.tracks.size();
                else p.currentIndex = -1;
                break;
            }
            case ServerProtocol.C2S_SET_PLAYLIST: {
                int n = d.readInt();
                if (n < 0 || n > 500) return;
                List<Party.TrackRef> nt = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String uri = d.readUTF();
                    String title = cap(d.readUTF(), 200);
                    if (uri.isEmpty() || uri.length() > 1000) continue;
                    nt.add(new Party.TrackRef(p.nextTrackId++, uri, title, player.getName()));
                }
                p.tracks.clear();
                p.tracks.addAll(nt);
                p.currentIndex = p.tracks.isEmpty() ? -1 : 0;
                p.paused = false;
                break;
            }
            case ServerProtocol.C2S_SET_PAUSED: {
                boolean v = d.readBoolean();
                if (v && !p.paused) p.pausedSince = System.currentTimeMillis();
                else if (!v && p.paused) { p.pausedAccum += System.currentTimeMillis() - p.pausedSince; p.pausedSince = 0; }
                p.paused = v;
                break;
            }
            case ServerProtocol.C2S_SET_AUTOREMOVE: p.autoRemovePlayed = d.readBoolean(); break;
            case ServerProtocol.C2S_SET_SPONSORBLOCK: p.sbFlags = (byte) (d.readByte() & 0x0F); break;
            case ServerProtocol.C2S_SET_REPEAT: p.repeatOne = d.readBoolean(); break;
            case ServerProtocol.C2S_SET_POSITION: {
                long ms = d.readLong();
                p.generation++;
                p.anchor(ms);
                for (UUID m : p.members.keySet()) { Player pl = Bukkit.getPlayer(m); if (pl != null) send(pl, ServerProtocol.seek(ms, p.generation)); }
                return;
            }
            case ServerProtocol.C2S_REANCHOR: { int gen = d.readInt(); long pos = d.readLong(); if (gen == p.generation && pos > p.elapsed()) p.anchor(pos); return; }
            default: return;
        }
        if (p.curTrackId() != oldCur) p.generation++;
        if (p.generation != genBefore) p.anchor(0);
        broadcast(p);
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private boolean allow(UUID u) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(u, k -> new Bucket(MSG_BURST, now));
        b.tokens = Math.min(MSG_BURST, b.tokens + (now - b.last) / 1000.0 * MSG_RATE);
        b.last = now;
        if (b.tokens < 1) return false;
        b.tokens--;
        return true;
    }

    public void forget(UUID u) { buckets.remove(u); }

    private void send(Player player, byte[] data) { player.sendPluginMessage(plugin, ServerProtocol.CHANNEL, data); }
}
