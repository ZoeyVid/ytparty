package com.mcyt.client.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

public class ClientNetworkHandler {
    public static final Identifier SYNC_CHANNEL = Identifier.of("mcyt", "sync");

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(SYNC_CHANNEL, (payload, context) -> {
            // Read all available bytes from payload buf, instead of using readByteArray() which expects a length prefix
            int length = payload.readableBytes();
            byte[] bytes = new byte[length];
            payload.readBytes(bytes);
            String jsonPayload = new String(bytes, StandardCharsets.UTF_8);

            context.client().execute(() -> {
                // Update local state based on jsonPayload
                // For a full implementation, we'd parse the JSON queue, play state, and volume
                System.out.println("Received sync: " + jsonPayload);
            });
        });
    }

    public void sendJoin(String partyId) {
        sendString("JOIN:" + partyId);
    }

    public void sendCreate(String partyId) {
        sendString("CREATE:" + partyId);
    }

    public void sendStateUpdate(String jsonState) {
        sendString(jsonState);
    }

    private void sendString(String payload) {
        if (ClientPlayNetworking.canSend(SYNC_CHANNEL)) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeBytes(payload.getBytes(StandardCharsets.UTF_8));
            ClientPlayNetworking.send(SYNC_CHANNEL, buf);
        }
    }
}
