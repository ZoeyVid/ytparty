package de.zoeyvid.ytparty;

import com.mojang.blaze3d.platform.InputConstants;
import de.zoeyvid.ytparty.gui.PlaylistScreen;
import de.zoeyvid.ytparty.net.ClientSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class YtPartyClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientSync.register();
        ClientConfig.load();

        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ytparty.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) client.setScreen(new PlaylistScreen());
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(Button.builder(Component.literal("YT Party"),
                    b -> client.setScreen(new PlaylistScreen())).bounds(4, 4, 80, 20).build());
        });
    }
}
