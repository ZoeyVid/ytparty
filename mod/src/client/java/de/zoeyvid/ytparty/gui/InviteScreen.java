package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.relay.RelayClient;
import de.zoeyvid.ytparty.net.SyncProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InviteScreen extends Screen {
    private static final int PICK_ROWS = 7;
    private int scroll;
    private EditBox filterField;
    private String savedFilter = "";
    private String lastSig = "";
    private boolean requestedPlayers;

    public InviteScreen() { super(Component.literal("Invite")); }

    private List<String> matches() {
        Minecraft mc = Minecraft.getInstance();
        String self = mc.getUser().getName();
        List<String> members = new ArrayList<>();
        for (SyncProtocol.Member m : PlayerController.INSTANCE.members()) members.add(m.name().toLowerCase(Locale.ROOT));
        List<String> names = new ArrayList<>();
        if (RelayClient.INSTANCE.connected()) names.addAll(PlayerController.INSTANCE.relayPlayers());
        else if (mc.getConnection() != null) for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) names.add(info.getProfile().name());
        String filter = savedFilter.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : names) if (!n.equalsIgnoreCase(self) && !members.contains(n.toLowerCase(Locale.ROOT)) && n.toLowerCase(Locale.ROOT).contains(filter)) out.add(n);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.hasParty() || !c.canInvite()) { this.minecraft.setScreenAndShow(c.hasParty() ? new PartyScreen() : new PlaylistScreen()); return; }
        if (RelayClient.INSTANCE.connected() && !requestedPlayers) { c.requestPlayerList(); requestedPlayers = true; }
        lastSig = signature();
        int left = this.width / 2 - 160;
        addRenderableWidget(new StringWidget(left, 12, 320, 12, Component.literal("Invite to party " + c.partyId()), this.font));
        filterField = new EditBox(this.font, left, 30, 320, 20, Component.literal("filter"));
        filterField.setHint(Component.literal("Search players"));
        filterField.setMaxLength(64);
        filterField.setValue(savedFilter);
        addRenderableWidget(filterField);
        setFocused(filterField);

        List<String> m = matches();
        scroll = Math.clamp(scroll, 0, Math.max(0, m.size() - PICK_ROWS));
        int rowTop = 56;
        int end = Math.min(m.size(), scroll + PICK_ROWS);
        for (int i = scroll; i < end; i++) {
            String name = m.get(i);
            int y = rowTop + (i - scroll) * 22;
            addRenderableWidget(new StringWidget(left, y + 6, 124, 12, Component.literal(name), this.font));
            for (byte lvl = 0; lvl <= 2; lvl++) {
                byte at = lvl;
                Button b = addRenderableWidget(Button.builder(Component.literal(PlaylistScreen.levelName(lvl)), btn -> c.invite(name, at)).tooltip(Tooltip.create(Component.literal("Invite as " + PlaylistScreen.levelName(lvl)))).bounds(left + 128 + lvl * 64, y, 60, 20).build());
                b.active = lvl <= c.myLevel();
            }
        }
        if (m.isEmpty()) addRenderableWidget(new StringWidget(left, rowTop + 4, 320, 12, Component.literal("No players online to invite"), this.font));
        else if (m.size() > PICK_ROWS) addRenderableWidget(new StringWidget(left, rowTop + PICK_ROWS * 22 + 2, 320, 12, Component.literal("\u2195 " + (scroll + 1) + "\u2013" + end + " / " + m.size()), this.font));

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreenAndShow(new PartyScreen())).bounds(left, this.height - 28, 320, 20).build());
    }

    @Override
    public void tick() {
        PlayerController c = PlayerController.INSTANCE;
        if (!c.hasParty() || !c.canInvite()) { this.minecraft.setScreenAndShow(c.hasParty() ? new PartyScreen() : new PlaylistScreen()); return; }
        if (filterField != null && !filterField.getValue().equals(savedFilter)) { savedFilter = filterField.getValue(); rebuildWidgets(); return; }
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
        sb.append(PlayerController.INSTANCE.partyId()).append('|').append(PlayerController.INSTANCE.myLevel());
        Minecraft mc = Minecraft.getInstance();
        if (RelayClient.INSTANCE.connected()) for (String n : PlayerController.INSTANCE.relayPlayers()) sb.append('@').append(n);
        else if (mc.getConnection() != null) for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) sb.append('|').append(p.getProfile().name());
        for (SyncProtocol.Member m : PlayerController.INSTANCE.members()) sb.append('#').append(m.name());
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
