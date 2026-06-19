package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class PartyScreen extends Screen {
    private static final int MAX_MEMBERS = 7;
    private EditBox inviteField;
    private String savedInvite = "";
    private byte inviteLevel = -1;
    private String lastSig = "";

    public PartyScreen() { super(Component.literal("Party")); }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.inParty()) { this.minecraft.setScreen(new PlaylistScreen()); return; }
        if (inviteLevel < 0) inviteLevel = (byte) Math.min(1, c.myLevel());
        inviteLevel = (byte) Math.min(inviteLevel, c.myLevel());
        lastSig = signature();

        int left = this.width / 2 - 160;
        int top = 28;
        addRenderableWidget(new StringWidget(left, 12, 320, 12,
            Component.literal("Party " + c.partyId() + " \u2014 " + PlaylistScreen.levelName(c.myLevel())), this.font));

        int y = top;
        if (c.canManage()) {
            addRenderableWidget(Button.builder(Component.literal("Public: " + (c.isPublic() ? "On" : "Off")),
                b -> c.setPublic(!c.isPublic(), c.publicJoinLevel())).bounds(left, y, 150, 20).build());
            byte joinLvl = c.publicJoinLevel();
            addRenderableWidget(Button.builder(Component.literal("Join as: " + PlaylistScreen.levelName(joinLvl)),
                b -> c.setPublic(c.isPublic(), (byte) (joinLvl >= 2 ? 0 : 2))).bounds(left + 154, y, 166, 20).build())
                .active = c.isPublic();
            y += 26;
        }

        List<SyncProtocol.Member> members = c.members();
        for (int i = 0; i < Math.min(members.size(), MAX_MEMBERS); i++) {
            SyncProtocol.Member m = members.get(i);
            net.minecraft.network.chat.MutableComponent label = Component.literal(m.name() + " \u2014 " + PlaylistScreen.levelName(m.level()));
            if (m.duplicate()) label.withStyle(net.minecraft.ChatFormatting.RED);
            addRenderableWidget(new StringWidget(left, y + 6, 200, 12, label, this.font));
            if (c.canManage())
                addRenderableWidget(Button.builder(Component.literal(PlaylistScreen.levelName(m.level())),
                    b -> c.setLevel(m.name(), (byte) ((m.level() + 1) % 3))).bounds(left + 210, y, 110, 20).build());
            y += 22;
        }

        y += 6;
        if (c.canInvite()) {
            inviteField = new EditBox(this.font, left, y, 150, 20, Component.literal("invite"));
            inviteField.setHint(Component.literal("Player name"));
            inviteField.setValue(savedInvite);
            addRenderableWidget(inviteField);
            addRenderableWidget(Button.builder(Component.literal(PlaylistScreen.levelName(inviteLevel)), b -> {
                inviteLevel = (byte) ((inviteLevel + 1) % (c.myLevel() + 1));
                b.setMessage(Component.literal(PlaylistScreen.levelName(inviteLevel)));
            }).bounds(left + 154, y, 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Invite"), b -> {
                String n = inviteField.getValue().trim();
                if (!n.isEmpty()) { c.invite(n, inviteLevel); inviteField.setValue(""); savedInvite = ""; }
            }).bounds(left + 248, y, 72, 20).build());
            y += 24;
            addRenderableWidget(Button.builder(Component.literal("Invite from list\u2026"), b -> this.minecraft.setScreen(new InviteScreen())).bounds(left, y, 320, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(new PlaylistScreen()))
            .bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.inParty()) { this.minecraft.setScreen(new PlaylistScreen()); return; }
        if (inviteField != null) savedInvite = inviteField.getValue();
        if (!signature().equals(lastSig)) rebuildWidgets();
    }

    private String signature() {
        PlayerController c = PlayerController.INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append(c.partyId()).append('|').append(c.myLevel()).append('|').append(c.isPublic()).append('|').append(c.publicJoinLevel());
        for (SyncProtocol.Member m : c.members()) sb.append('|').append(m.name()).append(':').append(m.level()).append(m.duplicate());
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
