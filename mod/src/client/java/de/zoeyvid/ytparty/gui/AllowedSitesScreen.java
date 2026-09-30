package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.audio.MusicPlayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AllowedSitesScreen extends Screen {
    private String text = String.join("\n", MusicPlayer.allowedSites());

    public AllowedSitesScreen() { super(Component.literal("Allowed sites")); }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 320, 12, Component.literal("Allowed sites \u2014 one URL prefix per line"), this.font));
        int top = 38 + addRenderableWidget(new MultiLineTextWidget(left, 30, Component.literal("URLs starting with one of these play through yt-dlp and ffmpeg (both must be installed), also when others add them; e.g. https://www.zdf.de allows every page on www.zdf.de. These sites see your IP address and can make your client request other addresses, even in your network."), this.font).setMaxWidth(320)).getHeight();

        MultiLineEditBox box = MultiLineEditBox.builder().setX(left).setY(top)
            .setPlaceholder(Component.literal("https://www.zdf.de"))
            .build(this.font, 320, this.height - 60 - top, Component.literal("Allowed sites"));
        box.setValue(text);
        box.setValueListener(v -> text = v);
        box.setTooltip(Tooltip.create(Component.literal("One URL prefix per line, e.g. https://www.zdf.de")));
        addRenderableWidget(box);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> { PlayerController.INSTANCE.setAllowedSites(text); ClientConfig.save(); this.minecraft.setScreenAndShow(new HudScreen()); })
            .tooltip(Tooltip.create(Component.literal("Save the list and go back"))).bounds(left, this.height - 52, 157, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.minecraft.setScreenAndShow(new HudScreen()))
            .tooltip(Tooltip.create(Component.literal("Discard changes and go back"))).bounds(left + 163, this.height - 52, 157, 20).build());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
