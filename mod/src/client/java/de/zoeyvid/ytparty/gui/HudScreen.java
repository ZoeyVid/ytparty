package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.audio.MusicPlayer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class HudScreen extends Screen {
    private static final List<Integer> VIDEO_POSITIONS = List.of(0, 1, 2, 5, 8, 7, 6, 3);

    public HudScreen() { super(Component.literal("Settings")); }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int top = 40;
        addRenderableWidget(new StringWidget(left, 16, 320, 12, Component.literal("Your settings"), this.font));
        addRenderableWidget(Button.builder(Component.literal("Now-Playing HUD: " + (ClientConfig.hudEnabled() ? "ON" : "OFF")),
            b -> { ClientConfig.setHudEnabled(!ClientConfig.hudEnabled()); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Show the now-playing overlay while music plays"))).bounds(left, top, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Corner: " + switch (ClientConfig.hudCorner()) { case 0 -> "Top-left"; case 1 -> "Top-right"; case 2 -> "Bottom-left"; default -> "Bottom-right"; }),
            b -> { ClientConfig.setHudCorner((ClientConfig.hudCorner() + 1) % 4); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Where the overlay sits on screen"))).bounds(left, top + 26, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Show: " + (ClientConfig.hudAlways() ? "Always" : "On track change")),
            b -> { ClientConfig.setHudAlways(!ClientConfig.hudAlways()); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Always visible, or only briefly on track change"))).bounds(left, top + 52, 320, 20).build());
        AbstractSliderButton video = new AbstractSliderButton(left, top + 78, 320, 20, Component.empty(), ClientConfig.videoEnabled() ? ClientConfig.videoSize() / 50.0 : 0) {
            { updateMessage(); }
            private int percent() { return (int) Math.round(value * 50); }
            @Override protected void updateMessage() { setMessage(Component.literal("Video: " + (percent() > 0 ? percent() + "% of the screen" : "OFF"))); }
            @Override protected void applyValue() { if (percent() > 0) ClientConfig.setVideoSize(percent()); ClientConfig.setVideoEnabled(percent() > 0); }
        };
        video.setTooltip(Tooltip.create(Component.literal("Largest share of the screen width and height for the picture-in-picture video, which keeps its own aspect ratio; 0 turns it off (needs ffmpeg installed)")));
        addRenderableWidget(video);
        addRenderableWidget(Button.builder(Component.literal("Video position: " + switch (ClientConfig.videoPosition()) { case 0 -> "Top-left"; case 1 -> "Top center"; case 2 -> "Top-right"; case 3 -> "Left middle"; case 5 -> "Right middle"; case 6 -> "Bottom-left"; case 7 -> "Bottom center"; default -> "Bottom-right"; }),
            b -> { ClientConfig.setVideoPosition(VIDEO_POSITIONS.get((VIDEO_POSITIONS.indexOf(ClientConfig.videoPosition()) + 1) % VIDEO_POSITIONS.size())); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Corner or edge where the video sits"))).bounds(left, top + 104, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Other sites: " + (MusicPlayer.otherSites ? "ON" : "OFF")),
            b -> { MusicPlayer.otherSites = !MusicPlayer.otherSites; ClientConfig.save(); rebuildWidgets(); }).tooltip(Tooltip.create(Component.literal("Also play URLs from other sites and their livestreams through yt-dlp, including ones others add; those sites see your IP address (needs yt-dlp and ffmpeg installed)"))).bounds(left, top + 130, 320, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .tooltip(Tooltip.create(Component.literal("Back to the playlist"))).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
