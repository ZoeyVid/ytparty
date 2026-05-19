package com.mcyt.client;

import com.mcyt.client.audio.AudioEngine;
import com.mcyt.client.gui.PlayerScreen;
import com.mcyt.client.network.ClientNetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class McytClientMod implements ClientModInitializer {
    public static final AudioEngine AUDIO_ENGINE = new AudioEngine();
    public static final ClientNetworkHandler NETWORK_HANDLER = new ClientNetworkHandler();
    private static KeyBinding openPlayerKey;

    @Override
    public void onInitializeClient() {
        NETWORK_HANDLER.register();

        openPlayerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mcyt.open", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
                GLFW.GLFW_KEY_Y, // The keycode of the key
                "category.mcyt.general" // The translation key of the keybinding's category.
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPlayerKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new PlayerScreen());
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AUDIO_ENGINE.stop();
        });
    }
}
