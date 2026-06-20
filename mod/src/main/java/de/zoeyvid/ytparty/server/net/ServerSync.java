package de.zoeyvid.ytparty.server.net;

import de.zoeyvid.ytparty.net.SyncPayload;
import de.zoeyvid.ytparty.server.party.Party;
import de.zoeyvid.ytparty.server.party.PartyManager;
import de.zoeyvid.ytparty.server.party.PermissionLevel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerSync {
    private static final double MSG_BURST = 16, MSG_RATE = 4;
    private final PartyManager manager = new PartyManager();
    private final Map<UUID, Bucket> buckets = new HashMap<>();
    private MinecraftServer server;

    private static final class Bucket {
        double tokens;
        long last;
        Bucket(double tokens, long last) { this.tokens = tokens; this.last = last; }
    }

    public PartyManager manager() { return manager; }
    public void setServer(MinecraftServer s) { server = s; }

    private String nameOf(UUID u) {
        ServerPlayer p = online(u);
        return p != null ? p.getName().getString() : u.toString().substring(0, 8);
    }

    private void send(ServerPlayer player, byte[] data) { ServerPlayNetworking.send(player, new SyncPayload(data)); }
    private ServerPlayer online(UUID u) { return server != null ? server.getPlayerList().getPlayer(u) : null; }
    private ServerPlayer byName(String name) { return server != null ? server.getPlayerList().getPlayerByName(name) : null; }

    private boolean allow(UUID u) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(u, k -> new Bucket(MSG_BURST, now));
        b.tokens = Math.min(MSG_BURST, b.tokens + (now - b.last) / 1000.0 * MSG_RATE);
        b.last = now;
        if (b.tokens < 1) return false;
        b.tokens--;
        return true;
    }
    private void forget(UUID u) { buckets.remove(u); }

    public void broadcast(Party party) {
        ServerProtocol.StateTemplate t = ServerProtocol.stateTemplate(party, this::nameOf);
        for (UUID m : party.members.keySet()) {
            ServerPlayer p = online(m);
            if (p == null) continue;
            byte[] msg = t.bytes().clone();
            msg[t.levelOffset()] = party.level(m).id();
            send(p, msg);
        }
    }

    public void afterLeave(PartyManager.LeaveResult r) {
        if (r == null) return;
        if (r.disbanded()) { for (UUID m : r.party().members.keySet()) { ServerPlayer p = online(m); if (p != null) send(p, ServerProtocol.left()); } }
        else broadcast(r.party());
    }

    public void create(ServerPlayer player) {
        afterLeave(manager.leave(player.getUUID()));
        broadcast(manager.create(player.getUUID()));
    }

    public void join(ServerPlayer player, String id) {
        UUID u = player.getUUID();
        Party target = manager.get(id);
        if (target == null || !(target.members.containsKey(u) || target.invites.containsKey(u) || target.isPublic)) { send(player, ServerProtocol.message("Cannot join " + id)); return; }
        if (target.members.containsKey(u)) { broadcast(target); return; }
        afterLeave(manager.leave(u));
        Party p = manager.join(u, id);
        if (p != null) broadcast(p);
    }

    public void leave(ServerPlayer player) {
        PartyManager.LeaveResult r = manager.leave(player.getUUID());
        send(player, ServerProtocol.left());
        afterLeave(r);
    }

    public void onDisconnect(ServerPlayer player, MinecraftServer s) {
        if (server == null) server = s;
        forget(player.getUUID());
        manager.forgetInvites(player.getUUID());
        afterLeave(manager.leave(player.getUUID()));
    }

    public void onReceive(ServerPlayer player, byte[] message) {
        if (message.length == 0) return;
        if (!allow(player.getUUID())) return;
        try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = d.readByte();
            switch (op) {
                case ServerProtocol.C2S_CREATE -> create(player);
                case ServerProtocol.C2S_JOIN -> join(player, d.readUTF());
                case ServerProtocol.C2S_LEAVE -> leave(player);
                case ServerProtocol.C2S_INVITE -> invite(player, d.readUTF(), PermissionLevel.fromId(d.readByte()));
                case ServerProtocol.C2S_SET_LEVEL -> setLevel(player, d.readUTF(), PermissionLevel.fromId(d.readByte()));
                case ServerProtocol.C2S_SET_PUBLIC -> setPublic(player, d.readBoolean(), PermissionLevel.fromId(d.readByte()));
                case ServerProtocol.C2S_LIST_PUBLIC -> send(player, ServerProtocol.publicList(manager.publicParties()));
                default -> handleControlOp(player, op, d);
            }
        } catch (IOException ignored) {}
    }

    private void invite(ServerPlayer player, String name, PermissionLevel req) {
        Party p = manager.of(player.getUUID());
        if (p == null || !p.canInvite(player.getUUID())) return;
        ServerPlayer target = byName(name);
        if (target == null) { send(player, ServerProtocol.message("Player not online: " + name)); return; }
        PermissionLevel granted = req.cappedTo(p.level(player.getUUID()));
        p.invites.put(target.getUUID(), granted);
        send(target, ServerProtocol.invited(player.getName().getString(), p.id, granted));
    }

    private void setLevel(ServerPlayer player, String name, PermissionLevel level) {
        Party p = manager.of(player.getUUID());
        if (p == null || !p.canManage(player.getUUID())) return;
        ServerPlayer target = byName(name);
        if (target == null) return;
        afterLeave(manager.setLevel(p, target.getUUID(), level));
    }

    private void setPublic(ServerPlayer player, boolean isPublic, PermissionLevel level) {
        Party p = manager.of(player.getUUID());
        if (p == null || !p.canManage(player.getUUID())) return;
        p.isPublic = isPublic;
        p.publicJoinLevel = level;
        broadcast(p);
    }

    private void handleControlOp(ServerPlayer player, byte op, DataInputStream d) throws IOException {
        Party p = manager.of(player.getUUID());
        if (p == null) return;
        if (!p.canManage(player.getUUID())) { send(player, ServerProtocol.state(p, player.getUUID(), this::nameOf)); return; }
        int oldCur = p.curTrackId();
        switch (op) {
            case ServerProtocol.C2S_ADD -> {
                String uri = d.readUTF();
                String title = cap(d.readUTF(), 200);
                if (uri.isEmpty() || uri.length() > 1000 || p.tracks.size() >= 500) return;
                p.tracks.add(new Party.TrackRef(p.nextTrackId++, uri, title, player.getName().getString()));
                if (p.currentIndex < 0) p.currentIndex = 0;
            }
            case ServerProtocol.C2S_REMOVE -> {
                int i = p.indexOf(d.readInt());
                if (i >= 0) {
                    p.tracks.remove(i);
                    if (i < p.currentIndex) p.currentIndex--;
                    if (p.currentIndex >= p.tracks.size()) p.currentIndex = p.tracks.size() - 1;
                }
            }
            case ServerProtocol.C2S_MOVE -> {
                int from = p.indexOf(d.readInt()), to = d.readInt();
                if (from >= 0) {
                    to = Math.max(0, Math.min(to, p.tracks.size() - 1));
                    p.move(from, to);
                    if (from == p.currentIndex) p.currentIndex = to;
                    else if (from < p.currentIndex && to >= p.currentIndex) p.currentIndex--;
                    else if (from > p.currentIndex && to <= p.currentIndex) p.currentIndex++;
                }
            }
            case ServerProtocol.C2S_SET_TRACK -> {
                int i = p.indexOf(d.readInt());
                if (i < 0) return;
                p.currentIndex = i;
                p.paused = false;
                p.mgrPos.clear();
            }
            case ServerProtocol.C2S_TRACK_ENDED -> {
                int gen = d.readInt();
                if (gen != p.generation || p.currentIndex < 0) return;
                if (p.autoRemovePlayed && p.currentIndex < p.tracks.size()) {
                    int cur = p.currentIndex;
                    p.tracks.remove(cur);
                    p.currentIndex = Math.min(cur, p.tracks.size() - 1);
                } else {
                    p.currentIndex = Math.min(p.currentIndex + 1, p.tracks.size() - 1);
                }
                p.mgrPos.clear();
            }
            case ServerProtocol.C2S_SET_PLAYLIST -> {
                int n = d.readInt();
                if (n < 0 || n > 500) return;
                List<Party.TrackRef> nt = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String uri = d.readUTF();
                    String title = cap(d.readUTF(), 200);
                    if (uri.isEmpty() || uri.length() > 1000) continue;
                    nt.add(new Party.TrackRef(p.nextTrackId++, uri, title, player.getName().getString()));
                }
                p.tracks.clear();
                p.tracks.addAll(nt);
                p.currentIndex = p.tracks.isEmpty() ? -1 : 0;
                p.paused = false;
                p.mgrPos.clear();
            }
            case ServerProtocol.C2S_SET_PAUSED -> p.paused = d.readBoolean();
            case ServerProtocol.C2S_SET_AUTOREMOVE -> p.autoRemovePlayed = d.readBoolean();
            case ServerProtocol.C2S_SET_SPONSORBLOCK -> p.sbFlags = (byte) (d.readByte() & 0x0F);
            case ServerProtocol.C2S_SET_REPEAT -> p.repeatOne = d.readBoolean();
            case ServerProtocol.C2S_REPORT_POSITION -> {
                int gen = d.readInt();
                long ms = d.readLong();
                if (gen != p.generation) return;
                p.mgrPos.put(player.getUUID(), new Party.MgrReport(ms, System.currentTimeMillis()));
                driftSeek(p);
                return;
            }
            case ServerProtocol.C2S_SET_POSITION -> {
                long ms = d.readLong();
                p.mgrPos.clear();
                for (UUID m : p.members.keySet()) { ServerPlayer pl = online(m); if (pl != null) send(pl, ServerProtocol.seek(ms)); }
                return;
            }
            default -> { return; }
        }
        if (p.curTrackId() != oldCur) p.generation++;
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
        for (UUID m : p.members.keySet()) { ServerPlayer pl = online(m); if (pl != null) send(pl, ServerProtocol.seek(med)); }
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
