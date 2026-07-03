package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HudScreen extends Screen {
    public HudScreen() { super(Component.literal("HUD")); }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int top = 40;
        addRenderableWidget(new StringWidget(left, 16, 320, 12, Component.literal("Now-Playing HUD \u2014 your settings"), this.font));
        addRenderableWidget(Button.builder(Component.literal("Now-Playing HUD: " + (ClientConfig.hudEnabled() ? "ON" : "OFF")),
            b -> { ClientConfig.setHudEnabled(!ClientConfig.hudEnabled()); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Show the now-playing overlay while music plays"))).bounds(left, top, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Corner: " + switch (ClientConfig.hudCorner()) { case 0 -> "Top-left"; case 1 -> "Top-right"; case 2 -> "Bottom-left"; default -> "Bottom-right"; }),
            b -> { ClientConfig.setHudCorner((ClientConfig.hudCorner() + 1) % 4); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Where the overlay sits on screen"))).bounds(left, top + 26, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Show: " + (ClientConfig.hudAlways() ? "Always" : "On track change")),
            b -> { ClientConfig.setHudAlways(!ClientConfig.hudAlways()); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Always visible, or only briefly on track change"))).bounds(left, top + 52, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .tooltip(Tooltip.create(Component.literal("Back to the playlist"))).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
