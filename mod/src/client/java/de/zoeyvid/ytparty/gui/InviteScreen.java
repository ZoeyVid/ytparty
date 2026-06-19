package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InviteScreen extends Screen {
    private static final int PICK_ROWS = 8;
    private EditBox search;
    private String savedSearch = "";
    private byte inviteLevel = -1;
    private int scroll;
    private boolean refocus = true;
    private String lastSig = "";

    public InviteScreen() { super(Component.literal("Invite")); }

    private List<String> matches() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) return out;
        String self = mc.getUser().getName();
        String f = savedSearch.trim().toLowerCase(Locale.ROOT);
        List<String> members = new ArrayList<>();
        for (SyncProtocol.Member m : PlayerController.INSTANCE.members()) members.add(m.name());
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name();
            if (n.equalsIgnoreCase(self) || members.contains(n)) continue;
            if (!f.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(f)) continue;
            out.add(n);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.inParty() || !c.canInvite()) { this.minecraft.setScreen(c.inParty() ? new PartyScreen() : new PlaylistScreen()); return; }
        if (inviteLevel < 0) inviteLevel = (byte) Math.min(1, c.myLevel());
        inviteLevel = (byte) Math.min(inviteLevel, c.myLevel());
        lastSig = signature();
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 320, 12, Component.literal("Invite to party " + c.partyId()), this.font));

        search = new EditBox(this.font, left, 30, 230, 20, Component.literal("search"));
        search.setHint(Component.literal("Search or type a name"));
        search.setMaxLength(64);
        search.setValue(savedSearch);
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal(PlaylistScreen.levelName(inviteLevel)), b -> {
            inviteLevel = (byte) ((inviteLevel + 1) % (c.myLevel() + 1));
            b.setMessage(Component.literal(PlaylistScreen.levelName(inviteLevel)));
        }).bounds(left + 234, 30, 86, 20).build());

        String typed = savedSearch.trim();
        addRenderableWidget(Button.builder(Component.literal(typed.isEmpty() ? "Type a name to invite" : "Invite \"" + typed + "\""),
            b -> { if (!typed.isEmpty()) c.invite(typed, inviteLevel); }).bounds(left, 54, 320, 20).build()).active = !typed.isEmpty();

        List<String> m = matches();
        scroll = Math.clamp(scroll, 0, Math.max(0, m.size() - PICK_ROWS));
        int rowTop = 80;
        int end = Math.min(m.size(), scroll + PICK_ROWS);
        for (int i = scroll; i < end; i++) {
            String name = m.get(i);
            addRenderableWidget(Button.builder(Component.literal(name), b -> c.invite(name, inviteLevel)).bounds(left, rowTop + (i - scroll) * 22, 320, 20).build());
        }
        if (m.isEmpty()) addRenderableWidget(new StringWidget(left, rowTop + 4, 320, 12, Component.literal(Minecraft.getInstance().getConnection() == null ? "Not on a server \u2014 type a name above" : "No matching players \u2014 type a name above"), this.font));
        else if (m.size() > PICK_ROWS) addRenderableWidget(new StringWidget(left, rowTop + PICK_ROWS * 22 + 2, 320, 12, Component.literal("\u2195 " + (scroll + 1) + "\u2013" + end + " / " + m.size()), this.font));

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(new PartyScreen())).bounds(left, this.height - 28, 320, 20).build());
        if (refocus) { setInitialFocus(search); search.moveCursorToEnd(false); }
    }

    @Override
    public void tick() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.inParty() || !c.canInvite()) { this.minecraft.setScreen(c.inParty() ? new PartyScreen() : new PlaylistScreen()); return; }
        if (search != null) savedSearch = search.getValue();
        refocus = search != null && search.isFocused();
        if (!signature().equals(lastSig)) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = matches().size();
        if (size > PICK_ROWS && dy != 0) {
            int next = Math.clamp(scroll - (int) Math.signum(dy), 0, size - PICK_ROWS);
            if (next != scroll) { scroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(savedSearch).append('|').append(scroll).append('|').append(inviteLevel).append('|').append(PlayerController.INSTANCE.partyId());
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) sb.append('|').append(p.getProfile().name());
        for (SyncProtocol.Member m : PlayerController.INSTANCE.members()) sb.append('#').append(m.name());
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
