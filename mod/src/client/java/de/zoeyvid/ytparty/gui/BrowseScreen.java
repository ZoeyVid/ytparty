package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class BrowseScreen extends Screen {
    private static final int MAX_ROWS = 8;
    private int lastVersion = -1;

    public BrowseScreen() { super(Component.literal("Public Parties")); }

    @Override
    protected void init() {
        if (!RelayClient.INSTANCE.connected()) { this.minecraft.setScreen(new PlaylistScreen()); return; }
        if (lastVersion < 0) PlayerController.INSTANCE.requestPublicList();
        lastVersion = PlayerController.INSTANCE.publicListVersion();
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 240, 12, Component.literal("Public parties on this relay"), this.font));
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> PlayerController.INSTANCE.requestPublicList()).bounds(left + 244, 5, 76, 20).build());

        List<SyncProtocol.PartyEntry> parties = PlayerController.INSTANCE.publicParties();
        int top = 30;
        if (parties.isEmpty()) {
            addRenderableWidget(new StringWidget(left, top + 8, 320, 12, Component.literal("No public parties right now."), this.font));
        } else {
            for (int i = 0; i < Math.min(parties.size(), MAX_ROWS); i++) {
                SyncProtocol.PartyEntry e = parties.get(i);
                int y = top + i * 24;
                String suffix = e.currentTitle().isEmpty() ? "" : " \u2014 " + trim(e.currentTitle());
                String label = e.id() + " \u2014 " + e.members() + (e.members() == 1 ? " member" : " members") + suffix;
                addRenderableWidget(new StringWidget(left, y + 6, 240, 12, Component.literal(label), this.font));
                String id = e.id();
                addRenderableWidget(Button.builder(Component.literal("Join"), b -> { PlayerController.INSTANCE.joinParty(id); }).bounds(left + 244, y, 76, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(new PlaylistScreen())).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        if (!RelayClient.INSTANCE.connected()) { this.minecraft.setScreen(new PlaylistScreen()); return; }
        if (PlayerController.INSTANCE.inParty()) { this.minecraft.setScreen(new PlaylistScreen()); return; }
        if (PlayerController.INSTANCE.publicListVersion() != lastVersion) rebuildWidgets();
    }

    private String trim(String s) { return s.length() <= 20 ? s : s.substring(0, 19) + "\u2026"; }

    @Override
    public boolean isPauseScreen() { return false; }
}
