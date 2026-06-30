package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class PartyScreen extends Screen {
    private static final int MAX_MEMBERS = 7;
    private String lastSig = "";
    private int scroll;

    public PartyScreen() { super(Component.literal("Party")); }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.hasParty()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        String self = this.minecraft.getUser().getName();
        lastSig = signature();

        int left = this.width / 2 - 160;
        int top = 28;
        addRenderableWidget(new StringWidget(left, 12, 320, 12,
            Component.literal("Party " + c.partyId() + " \u2014 " + PlaylistScreen.levelName(c.myLevel())), this.font));

        int y = top;
        if (c.canManage()) {
            addRenderableWidget(Button.builder(Component.literal("Public: " + (c.isPublic() ? "On" : "Off")),
                b -> c.setPublic(!c.isPublic(), c.publicJoinLevel())).tooltip(Tooltip.create(Component.literal("Anyone can join a public party without an invite"))).bounds(left, y, 150, 20).build());
            byte joinLvl = c.publicJoinLevel();
            addRenderableWidget(Button.builder(Component.literal("Join as: " + PlaylistScreen.levelName(joinLvl)),
                b -> c.setPublic(c.isPublic(), (byte) (joinLvl >= 2 ? 0 : 2))).tooltip(Tooltip.create(Component.literal("Level that public joiners receive"))).bounds(left + 154, y, 166, 20).build())
                .active = c.isPublic();
            y += 26;
        }

        List<SyncProtocol.Member> members = c.members();
        scroll = Math.clamp(scroll, 0, Math.max(0, members.size() - MAX_MEMBERS));
        int end = Math.min(members.size(), scroll + MAX_MEMBERS);
        for (int i = scroll; i < end; i++) {
            SyncProtocol.Member m = members.get(i);
            net.minecraft.network.chat.MutableComponent label = Component.literal(m.name() + " \u2014 " + PlaylistScreen.levelName(m.level()));
            addRenderableWidget(new StringWidget(left, y + 6, 150, 12, label, this.font));
            if (c.canManage()) {
                addRenderableWidget(Button.builder(Component.literal(PlaylistScreen.levelName(m.level())),
                    b -> c.setLevel(m.name(), (byte) ((m.level() + 1) % 3))).tooltip(Tooltip.create(Component.literal("Click to change this member's level"))).bounds(left + 154, y, 140, 20).build());
                if (!m.name().equalsIgnoreCase(self))
                    addRenderableWidget(Button.builder(Component.literal("\u2715"), b -> c.kick(m.name())).tooltip(Tooltip.create(Component.literal("Remove from party"))).bounds(left + 298, y, 22, 20).build());
            }
            y += 22;
        }
        if (members.size() > MAX_MEMBERS) { addRenderableWidget(new StringWidget(left, y, 320, 12, Component.literal("\u2195 " + (scroll + 1) + "\u2013" + end + " / " + members.size()), this.font)); y += 14; }

        y += 6;
        if (c.canInvite())
            addRenderableWidget(Button.builder(Component.literal("Invite players\u2026"), b -> this.minecraft.setScreenAndShow(new InviteScreen())).tooltip(Tooltip.create(Component.literal("Invite online players"))).bounds(left, y, 320, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.hasParty()) { this.minecraft.setScreenAndShow(new PlaylistScreen()); return; }
        if (!signature().equals(lastSig)) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = PlayerController.INSTANCE.members().size();
        if (size > MAX_MEMBERS && dy != 0) {
            int next = Math.clamp(scroll - (int) Math.signum(dy), 0, size - MAX_MEMBERS);
            if (next != scroll) { scroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private String signature() {
        PlayerController c = PlayerController.INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append(c.partyId()).append('|').append(c.myLevel()).append('|').append(c.isPublic()).append('|').append(c.publicJoinLevel());
        for (SyncProtocol.Member m : c.members()) sb.append('|').append(m.name()).append(':').append(m.level());
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
