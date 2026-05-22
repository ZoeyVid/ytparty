package com.mcyt.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class NetworkSender {
    public static void sendPause(boolean paused) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "pause");
        json.addProperty("paused", paused);
        send(json);
    }
    
    public static void sendPlayTrack(String url) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "play_track");
        json.addProperty("url", url);
        send(json);
    }
    
    public static void sendReorder(List<String> newPlaylist) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "reorder");
        JsonArray arr = new JsonArray();
        for (String url : newPlaylist) arr.add(url);
        json.add("playlist", arr);
        send(json);
    }

    private static void send(JsonObject json) {
        String data = json.toString();
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeBytes(data.getBytes(StandardCharsets.UTF_8));
        ClientPlayNetworking.send(McytModClient.ACTION_CHANNEL, buf);
    }
}

