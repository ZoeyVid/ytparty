package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class InvitesScreen extends Screen {
    private static final int MAX_ROWS = 8;
    private int scroll;
    private int lastCount = -1;

    public InvitesScreen() { super(Component.literal("Invites")); }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        List<PlayerController.Invite> inv = c.pendingInvites();
        if (c.hasParty() || inv.isEmpty()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        lastCount = inv.size();
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 320, 12, Component.literal("Pending invites"), this.font));

        scroll = Math.clamp(scroll, 0, Math.max(0, inv.size() - MAX_ROWS));
        int top = 32;
        int end = Math.min(inv.size(), scroll + MAX_ROWS);
        for (int i = scroll; i < end; i++) {
            PlayerController.Invite e = inv.get(i);
            int y = top + (i - scroll) * 22;
            addRenderableWidget(new StringWidget(left, y + 6, 150, 12, Component.literal("from " + e.from()), this.font));
            addRenderableWidget(Button.builder(Component.literal("Join as " + PlaylistScreen.levelName(e.level())), b -> c.acceptInvite(e.id()))
                .tooltip(Tooltip.create(Component.literal("Party " + e.id()))).bounds(left + 154, y, 122, 20).build());
            addRenderableWidget(Button.builder(Component.literal("\u2715"), b -> { c.dismissInvite(e.id()); rebuildWidgets(); })
                .tooltip(Tooltip.create(Component.literal("Dismiss"))).bounds(left + 280, y, 40, 20).build());
        }
        if (inv.size() > MAX_ROWS)
            addRenderableWidget(new StringWidget(left, top + MAX_ROWS * 22 + 2, 320, 12, Component.literal("\u2195 " + (scroll + 1) + "\u2013" + end + " / " + inv.size()), this.font));

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen())).tooltip(Tooltip.create(Component.literal("Back to the playlist"))).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        PlayerController c = PlayerController.INSTANCE;
        if (c.hasParty() || c.pendingInvites().isEmpty()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        if (c.pendingInvites().size() != lastCount) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = PlayerController.INSTANCE.pendingInvites().size();
        if (size > MAX_ROWS && dy != 0) {
            int next = Math.clamp(scroll - (int) Math.signum(dy), 0, size - MAX_ROWS);
            if (next != scroll) { scroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
