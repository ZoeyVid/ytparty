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
import java.util.Map;
import java.util.UUID;

public final class ServerSync {
    private final PartyManager manager = new PartyManager();
    private MinecraftServer server;

    public PartyManager manager() { return manager; }
    public void setServer(MinecraftServer s) { server = s; }

    private String nameOf(UUID u) {
        ServerPlayer p = online(u);
        return p != null ? p.getName().getString() : u.toString().substring(0, 8);
    }

    private void send(ServerPlayer player, byte[] data) { ServerPlayNetworking.send(player, new SyncPayload(data)); }
    private ServerPlayer online(UUID u) { return server != null ? server.getPlayerList().getPlayer(u) : null; }
    private ServerPlayer byName(String name) { return server != null ? server.getPlayerList().getPlayerByName(name) : null; }

    public void broadcast(Party party) {
        for (UUID m : party.members.keySet()) { ServerPlayer p = online(m); if (p != null) send(p, ServerProtocol.state(party, m, this::nameOf)); }
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
        afterLeave(manager.leave(player.getUUID()));
    }

    public void onReceive(ServerPlayer player, byte[] message) {
        if (message.length == 0) return;
        try (DataInputStream d = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = d.readByte();
            switch (op) {
                case ServerProtocol.C2S_CREATE -> create(player);
                case ServerProtocol.C2S_JOIN -> join(player, d.readUTF());
                case ServerProtocol.C2S_LEAVE -> leave(player);
                case ServerProtocol.C2S_INVITE -> invite(player, d.readUTF(), PermissionLevel.fromId(d.readByte()));
                case ServerProtocol.C2S_SET_LEVEL -> setLevel(player, d.readUTF(), PermissionLevel.fromId(d.readByte()));
                case ServerProtocol.C2S_SET_PUBLIC -> setPublic(player, d.readBoolean(), PermissionLevel.fromId(d.readByte()));
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
        switch (op) {
            case ServerProtocol.C2S_ADD -> {
                String uri = cap(d.readUTF(), 1000);
                String title = cap(d.readUTF(), 200);
                if (p.tracks.size() >= 500) return;
                p.tracks.add(new Party.TrackRef(uri, title));
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
            }
            case ServerProtocol.C2S_SET_PAUSED -> p.paused = d.readBoolean();
            case ServerProtocol.C2S_SET_AUTOREMOVE -> p.autoRemovePlayed = d.readBoolean();
            case ServerProtocol.C2S_SET_POSITION -> {
                long ms = d.readLong();
                for (UUID m : p.members.keySet()) { ServerPlayer pl = online(m); if (pl != null) send(pl, ServerProtocol.seek(ms)); }
                return;
            }
            default -> { return; }
        }
        broadcast(p);
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
