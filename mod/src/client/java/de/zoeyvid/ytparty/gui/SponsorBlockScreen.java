package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.audio.SponsorBlock;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SponsorBlockScreen extends Screen {
    private byte lastFlags = -1;

    public SponsorBlockScreen() { super(Component.literal("SponsorBlock")); }

    private byte flags() { return PlayerController.INSTANCE.hasParty() ? PlayerController.INSTANCE.partySbFlags() : ClientConfig.sbFlags(); }

    private boolean editable() {
        PlayerController c = PlayerController.INSTANCE;
        return !c.hasParty() || c.canManage();
    }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        byte flags = flags();
        lastFlags = flags;
        int left = this.width / 2 - 160;
        int top = 30;
        addRenderableWidget(new StringWidget(left, 12, 320, 12,
            Component.literal(c.hasParty() ? "SponsorBlock \u2014 party (managers control)" : "SponsorBlock \u2014 your settings"), this.font));

        toggle(left, top, "SponsorBlock", flags, SponsorBlock.FLAG_ENABLED);
        boolean on = (flags & SponsorBlock.FLAG_ENABLED) != 0;
        toggle(left, top + 26, "Sponsor segments", flags, SponsorBlock.FLAG_SPONSOR).active = editable() && on;
        toggle(left, top + 52, "Unpaid / self-promotion", flags, SponsorBlock.FLAG_SELFPROMO).active = editable() && on;
        toggle(left, top + 78, "Music: non-music section", flags, SponsorBlock.FLAG_MUSIC).active = editable() && on;

        if (c.hasParty() && !c.canManage())
            addRenderableWidget(new StringWidget(left, top + 106, 320, 12, Component.literal("Only a manager can change these."), this.font));

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .bounds(left, this.height - 28, 320, 20).build());
    }

    private Button toggle(int x, int y, String label, byte flags, byte bit) {
        boolean set = (flags & bit) != 0;
        Button b = Button.builder(Component.literal(label + ": " + (set ? "ON" : "OFF")),
            btn -> PlayerController.INSTANCE.setSponsorBlock((byte) (flags() ^ bit))).bounds(x, y, 320, 20).build();
        b.active = editable();
        addRenderableWidget(b);
        return b;
    }

    @Override
    public void tick() {
        if (flags() != lastFlags) rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
