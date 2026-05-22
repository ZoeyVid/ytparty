package com.mcyt.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

public class McytModClient implements ClientModInitializer {
    
    public static final Identifier SYNC_CHANNEL = Identifier.of("mcyt", "sync");
    public static final Identifier ACTION_CHANNEL = Identifier.of("mcyt", "action");

    private static McytModClient instance;
    private AudioEngine audioEngine = new AudioEngine();

    public McytModClient() {
        instance = this;
    }

    public static McytModClient getInstance() {
        return instance;
    }

    public AudioEngine getAudioEngine() {
        return audioEngine;
    }

    @Override
    public void onInitializeClient() {
        System.out.println("Initializing MCYT Audio Mod...");

        KeybindInitializer.register();

        // Listen to the server
        ClientPlayNetworking.registerGlobalReceiver(SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            System.out.println("Received sync: " + jsonStr);
            
            JsonObject state = JsonParser.parseString(jsonStr).getAsJsonObject();
            
            client.execute(() -> {
                audioEngine.syncStateFromServer(state);
            });
        });
    }
}




