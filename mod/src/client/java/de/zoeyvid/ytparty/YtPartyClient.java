package de.zoeyvid.ytparty;

import com.mojang.blaze3d.platform.InputConstants;
import de.zoeyvid.ytparty.gui.NowPlayingHud;
import de.zoeyvid.ytparty.gui.PlaylistScreen;
import de.zoeyvid.ytparty.gui.VideoHud;
import de.zoeyvid.ytparty.net.ClientSync;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class YtPartyClient implements ClientModInitializer {
    private boolean autoConnectDone;

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ClientSync.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ytparty", "now_playing"), new NowPlayingHud());
        VideoHud videoHud = new VideoHud();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ytparty", "video"), videoHud);

        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ytparty.open", InputConstants.KEY_J, KeyMapping.Category.MISC));
        KeyMapping video = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ytparty.video", InputConstants.KEY_K, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PlayerController.INSTANCE.tick();
            if (!autoConnectDone) {
                autoConnectDone = true;
                if (RelayClient.autoConnect && !RelayClient.host.isEmpty())
                    try { RelayClient.INSTANCE.connect(RelayClient.host, Integer.parseInt(RelayClient.port), RelayClient.password); } catch (NumberFormatException ignored) {}
            }
            while (open.consumeClick()) client.setScreenAndShow(new PlaylistScreen());
            while (video.consumeClick()) { ClientConfig.setVideoEnabled(!ClientConfig.videoEnabled()); ClientSync.message("Video: " + (ClientConfig.videoEnabled() ? "ON" : "OFF")); }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ScreenEvents.afterBackground(screen).register((s, g, mouseX, mouseY, delta) -> videoHud.draw(g));
            if (screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(Button.builder(Component.literal("YT Party"),
                    b -> client.setScreenAndShow(new PlaylistScreen())).bounds(4, 4, 80, 20).build());
        });
    }
}
