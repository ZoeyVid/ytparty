package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class HudScreen extends Screen {
    private static final List<Integer> VIDEO_SIZES = List.of(128, 160, 240, 320);

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
        addRenderableWidget(Button.builder(Component.literal("Video: " + (ClientConfig.videoEnabled() ? "ON" : "OFF")),
            b -> { ClientConfig.setVideoEnabled(!ClientConfig.videoEnabled()); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Small picture-in-picture video in the same corner (needs ffmpeg installed)"))).bounds(left, top + 78, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Video size: " + ClientConfig.videoSize() + "\u00D7" + ClientConfig.videoSize() * 9 / 16),
            b -> { ClientConfig.setVideoSize(VIDEO_SIZES.get((VIDEO_SIZES.indexOf(ClientConfig.videoSize()) + 1) % VIDEO_SIZES.size())); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Width \u00D7 height of the video in GUI pixels"))).bounds(left, top + 104, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .tooltip(Tooltip.create(Component.literal("Back to the playlist"))).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
