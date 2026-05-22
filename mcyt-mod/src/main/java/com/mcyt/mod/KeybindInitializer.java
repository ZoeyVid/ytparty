package com.mcyt.mod;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KeybindInitializer {
    private static KeyBinding openGuiKeyBind;

    public static void register() {
        openGuiKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mcyt.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.mcyt.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKeyBind.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new McytScreen());
                }
            }
        });
    }
}
