package de.zoeyvid.ytparty.net;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.DataInputStream;
import java.io.IOException;

public final class ClientSync {
    private static final PlayerController.Sink SERVER_SINK = data -> {
        if (ClientPlayNetworking.canSend(SyncPayload.TYPE)) ClientPlayNetworking.send(new SyncPayload(data));
    };

    private ClientSync() {}

    public static PlayerController.Sink serverSink() { return SERVER_SINK; }

    public static boolean backendAvailable() { return RelayClient.INSTANCE.connected() || ClientPlayNetworking.canSend(SyncPayload.TYPE); }

    public static void register() {
        PlayerController.INSTANCE.setSink(SERVER_SINK);

        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE,
            (payload, context) -> { if (RelayClient.INSTANCE.connected()) return; context.client().execute(() -> handle(payload.data())); });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (RelayClient.INSTANCE.connected()) return;
            boolean stopAudio = PlayerController.INSTANCE.inParty() && !client.hasSingleplayerServer();
            PlayerController.INSTANCE.onWorldDisconnect(stopAudio);
        });
    }

    public static void handle(byte[] data) {
        if (data.length == 0) return;
        try (DataInputStream d = SyncProtocol.reader(data)) {
            byte op = d.readByte();
            switch (op) {
                case SyncProtocol.S2C_STATE -> PlayerController.INSTANCE.applyState(SyncProtocol.readState(d));
                case SyncProtocol.S2C_SEEK -> PlayerController.INSTANCE.applyRemoteSeek(d.readLong());
                case SyncProtocol.S2C_LEFT -> PlayerController.INSTANCE.onPartyLeft();
                case SyncProtocol.S2C_INVITED -> {
                    String from = d.readUTF();
                    String id = d.readUTF();
                    byte level = d.readByte();
                    PlayerController.INSTANCE.onInvited(from, id, level);
                    message("Party invite from " + from + " (open J to join)");
                }
                case SyncProtocol.S2C_MESSAGE -> message(d.readUTF());
                case SyncProtocol.S2C_PUBLIC_LIST -> {
                    int count = d.readInt();
                    java.util.List<SyncProtocol.PartyEntry> entries = new java.util.ArrayList<>();
                    for (int i = 0; i < count; i++) entries.add(new SyncProtocol.PartyEntry(d.readUTF(), d.readInt(), d.readUTF()));
                    PlayerController.INSTANCE.onPublicList(entries);
                }
                case SyncProtocol.S2C_PLAYER_LIST -> {
                    int count = d.readInt();
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (int i = 0; i < count; i++) names.add(d.readUTF());
                    PlayerController.INSTANCE.onPlayerList(names);
                }
                default -> {}
            }
        } catch (IOException ignored) {}
    }

    private static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }
}
