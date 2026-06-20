package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.net.ClientSync;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class BrowseScreen extends Screen {
    private static final int MAX_ROWS = 8;
    private int lastVersion = -1;
    private int scroll;

    public BrowseScreen() { super(Component.literal("Public Parties")); }

    @Override
    protected void init() {
        if (!ClientSync.backendAvailable()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        if (lastVersion < 0) PlayerController.INSTANCE.requestPublicList();
        lastVersion = PlayerController.INSTANCE.publicListVersion();
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 240, 12, Component.literal("Public parties"), this.font));
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> PlayerController.INSTANCE.requestPublicList()).bounds(left + 244, 5, 76, 20).build());

        List<SyncProtocol.PartyEntry> parties = PlayerController.INSTANCE.publicParties();
        int top = 30;
        if (parties.isEmpty()) {
            addRenderableWidget(new StringWidget(left, top + 8, 320, 12, Component.literal("No public parties right now."), this.font));
        } else {
            scroll = Math.clamp(scroll, 0, Math.max(0, parties.size() - MAX_ROWS));
            int end = Math.min(parties.size(), scroll + MAX_ROWS);
            for (int i = scroll; i < end; i++) {
                SyncProtocol.PartyEntry e = parties.get(i);
                int y = top + (i - scroll) * 24;
                String suffix = e.currentTitle().isEmpty() ? "" : " \u2014 " + trim(e.currentTitle());
                String label = e.id() + " \u2014 " + e.members() + (e.members() == 1 ? " member" : " members") + suffix;
                addRenderableWidget(new StringWidget(left, y + 6, 240, 12, Component.literal(label), this.font));
                String id = e.id();
                addRenderableWidget(Button.builder(Component.literal("Join"), b -> { PlayerController.INSTANCE.joinParty(id); }).bounds(left + 244, y, 76, 20).build());
            }
            if (parties.size() > MAX_ROWS)
                addRenderableWidget(new StringWidget(left, top + MAX_ROWS * 24 + 2, 320, 12, Component.literal("\u2195 " + (scroll + 1) + "\u2013" + end + " / " + parties.size()), this.font));
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen())).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        if (!ClientSync.backendAvailable()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        if (PlayerController.INSTANCE.inParty()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        if (PlayerController.INSTANCE.publicListVersion() != lastVersion) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = PlayerController.INSTANCE.publicParties().size();
        if (size > MAX_ROWS && dy != 0) {
            int next = Math.clamp(scroll - (int) Math.signum(dy), 0, size - MAX_ROWS);
            if (next != scroll) { scroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private String trim(String s) { return s.length() <= 20 ? s : s.substring(0, 19) + "\u2026"; }

    @Override
    public boolean isPauseScreen() { return false; }
}
