package com.mcyt.mod;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class KeybindInitializer {
    private static KeyMapping openGuiKeyBind;

    public static void register() {
        openGuiKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mcyt.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.mcyt.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKeyBind.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new McytScreen());
                }
            }
        });
    }
}
