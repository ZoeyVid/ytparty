package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.playlist.Track;
import de.zoeyvid.ytparty.net.ClientSync;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class PlaylistScreen extends Screen {
    private static final int MAX_ROWS = 7;
    private EditBox urlField;
    private Timeline timeline;
    private String savedUrl = "";
    private String lastSig = "";
    private int scrollOffset;
    private int dragFrom = -1;
    private int dragTo = -1;
    private int listTop;
    private int listLeft;
    private int visRows;
    private boolean canEdit;

    public PlaylistScreen() { super(Component.literal("YT Party")); }

    static String levelName(byte l) { return l >= 2 ? "Manage" : l == 1 ? "Invite" : "Listen"; }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        int left = this.width / 2 - 160;
        int top = 24;
        lastSig = signature();

        addRenderableWidget(new StringWidget(left, 10, 320, 12,
            Component.literal(c.inParty() ? "Party " + c.partyId() + " \u2014 " + levelName(c.myLevel())
                : (RelayClient.INSTANCE.connected() ? "Relay connected" : "Local")), this.font));

        urlField = new EditBox(this.font, left, top, 200, 20, Component.literal("YouTube URL"));
        urlField.setMaxLength(2048);
        urlField.setHint(Component.literal("YouTube video URL"));
        urlField.setValue(savedUrl);
        addRenderableWidget(urlField);
        btn("Add", () -> {
            String text = urlField.getValue().trim();
            if (!text.isEmpty()) { c.addUrl(text, null); urlField.setValue(""); savedUrl = ""; }
        }, "Add the URL to the playlist", left + 204, top, 38);
        btn("Edit list\u2026", () -> this.minecraft.setScreenAndShow(new PlaylistEditScreen()), "Edit the whole playlist as a text list of URLs", left + 246, top, 74);

        canEdit = !c.inParty() || c.canManage();

        btn("SponsorBlock\u2026", () -> this.minecraft.setScreenAndShow(new SponsorBlockScreen()), "SponsorBlock segment-skip settings", left, top + 26, 120);
        btn("HUD\u2026", () -> this.minecraft.setScreenAndShow(new HudScreen()), "Now-Playing HUD settings", left + 124, top + 26, 60);
        AbstractSliderButton vol = new AbstractSliderButton(left + 188, top + 26, 132, 20, Component.literal("Vol " + c.volume()), c.volume() / 200.0) {
            @Override protected void updateMessage() { setMessage(Component.literal("Vol " + (int) (value * 200))); }
            @Override protected void applyValue() { c.setVolume((int) (value * 200)); }
            @Override public void onRelease(net.minecraft.client.input.MouseButtonEvent e) { super.onRelease(e); ClientConfig.save(); }
        };
        vol.setTooltip(Tooltip.create(Component.literal("Volume")));
        addRenderableWidget(vol);

        timeline = new Timeline(left, top + 52, 320, 20);
        timeline.setTooltip(Tooltip.create(Component.literal("Seek within the current track")));
        addRenderableWidget(timeline);

        btn("\u23EE", c::previous, "Previous track", left, top + 78, 22).active = canEdit;
        btn("\u21E4", () -> seek(0), "Restart current track", left + 24, top + 78, 22).active = canEdit;
        btn(c.paused() ? "\u25B6" : "\u23F8", c::togglePause, c.paused() ? "Play" : "Pause", left + 48, top + 78, 22).active = canEdit;
        btn("\u23ED", c::skip, "Skip to next track", left + 72, top + 78, 22).active = canEdit;
        btn("Repeat: " + (c.repeatOne() ? "ON" : "OFF"), c::toggleRepeat, "Repeat the current track", left + 98, top + 78, 74).active = canEdit;
        btn("Auto-remove: " + (c.autoRemovePlayed() ? "ON" : "OFF"), c::toggleAutoRemove, "Remove each track once it finishes playing", left + 176, top + 78, 120).active = canEdit;
        btn("\u2913", () -> {
            int idx = c.currentIndex();
            if (idx >= 0) { scrollOffset = Math.clamp(idx, 0, Math.max(0, c.playlist().size() - MAX_ROWS)); rebuildWidgets(); }
        }, "Scroll to the current track", left + 298, top + 78, 22).active = c.currentIndex() >= 0;

        partyRow(c, left, top + 104);

        List<Track> tracks = c.playlist().view();
        listLeft = left;
        listTop = top + 130;
        int maxOffset = Math.max(0, tracks.size() - MAX_ROWS);
        scrollOffset = Math.clamp(scrollOffset, 0, maxOffset);
        int end = Math.min(tracks.size(), scrollOffset + MAX_ROWS);
        visRows = end - scrollOffset;

        boolean dragging = dragFrom >= 0 && dragTo >= 0;
        List<Track> order = tracks;
        int marked = -1;
        if (dragging) {
            order = new java.util.ArrayList<>(tracks);
            Track moved = order.remove(Math.clamp(dragFrom, 0, order.size() - 1));
            marked = Math.clamp(dragTo, 0, order.size());
            order.add(marked, moved);
        }
        for (int i = scrollOffset; i < end; i++) {
            int row = i;
            int y = listTop + (i - scrollOffset) * 22;
            if (dragging) {
                addRenderableWidget(new StringWidget(left + 24, y + 6, 280, 12, Component.literal((i == marked ? "\u2261 " : "") + trim(order.get(i).title(), 26)), this.font));
                continue;
            }
            boolean current = i == c.currentIndex();
            addRenderableWidget(new StringWidget(left + 24, y + 6, 252, 12, trackComponent(order.get(i), current, c), this.font));
            if (current) btn(c.paused() ? "\u25B6" : "\u23F8", c::togglePause, c.paused() ? "Play" : "Pause", left, y, 20).active = canEdit;
            else btn("\u25B6", () -> c.playIndex(row), "Play this track", left, y, 20).active = canEdit;
            btn("\u2715", () -> c.removeAt(row), "Remove from the playlist", left + 300, y, 20).active = canEdit;
        }
        if (tracks.size() > MAX_ROWS)
            addRenderableWidget(new StringWidget(left, listTop + MAX_ROWS * 22 + 2, 320, 12, Component.literal("\u2195 " + (scrollOffset + 1) + "\u2013" + end + " / " + tracks.size()), this.font));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean d) {
        if (super.mouseClicked(e, d)) return true;
        if (e.button() == 0 && canEdit) {
            int idx = rowAtForStart(e.y());
            if (idx >= 0) { dragFrom = idx; dragTo = -1; return true; }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragFrom >= 0) {
            int to = rowAtForDrop(e.y());
            if (to != dragTo) { dragTo = to; rebuildWidgets(); }
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragFrom >= 0) {
            int from = dragFrom, to = dragTo;
            dragFrom = -1; dragTo = -1;
            if (to >= 0 && to != from) PlayerController.INSTANCE.move(from, to);
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(e);
    }

    private int rowAtForStart(double my) {
        if (my < listTop || my >= listTop + visRows * 22) return -1;
        int idx = scrollOffset + (int) ((my - listTop) / 22);
        int size = PlayerController.INSTANCE.playlist().size();
        return idx >= 0 && idx < size ? idx : -1;
    }

    private int rowAtForDrop(double my) {
        int size = PlayerController.INSTANCE.playlist().size();
        return Math.clamp(scrollOffset + (int) ((my - listTop) / 22), 0, Math.max(0, size - 1));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = PlayerController.INSTANCE.playlist().size();
        if (size > MAX_ROWS && dy != 0) {
            int next = Math.clamp(scrollOffset - (int) Math.signum(dy), 0, size - MAX_ROWS);
            if (next != scrollOffset) { scrollOffset = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void partyRow(PlayerController c, int left, int y) {
        if (c.inParty()) {
            btn("Party\u2026", () -> this.minecraft.setScreenAndShow(new PartyScreen()), "Manage members and invites", left, y, 240);
            btn("Leave", c::leaveParty, "Leave the party", left + 244, y, 76);
        } else if (c.pendingInviteId() != null) {
            btn("Create", c::createParty, "Create a new listening party", left, y, 110);
            btn("Join " + c.pendingInviteId(), c::acceptInvite, "Accept the invite and join", left + 114, y, 120);
            btn("Relay", () -> this.minecraft.setScreenAndShow(new RelayScreen()), "Relay connection settings", left + 238, y, 82);
        } else if (ClientSync.backendAvailable()) {
            btn("Create party", c::createParty, "Create a new listening party", left, y, 152);
            btn("Browse\u2026", () -> this.minecraft.setScreenAndShow(new BrowseScreen()), "Browse public parties", left + 156, y, 80);
            btn("Relay", () -> this.minecraft.setScreenAndShow(new RelayScreen()), "Relay connection settings", left + 240, y, 80);
        } else {
            btn("Create party", c::createParty, "Create a new listening party", left, y, 235);
            btn("Relay", () -> this.minecraft.setScreenAndShow(new RelayScreen()), "Relay connection settings", left + 239, y, 81);
        }
    }

    private Button btn(String label, Runnable onClick, String tip, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> onClick.run())
            .tooltip(Tooltip.create(Component.literal(tip))).bounds(x, y, w, 20).build());
    }

    @Override
    public void tick() {
        if (urlField != null) savedUrl = urlField.getValue();
        if (timeline != null) timeline.refresh();
        if (!signature().equals(lastSig)) rebuildWidgets();
    }

    private void seek(long ms) {
        long dur = PlayerController.INSTANCE.duration();
        long target = Math.max(0, dur > 0 ? Math.min(ms, dur) : ms);
        PlayerController.INSTANCE.seekTo(target);
        if (timeline != null) timeline.hold(target);
    }

    private String signature() {
        PlayerController c = PlayerController.INSTANCE;
        return c.inParty() + "|" + c.partyId() + "|" + c.paused() + "|" + c.currentIndex() + "|" + c.myLevel()
            + "|" + c.pendingInviteId() + "|" + RelayClient.INSTANCE.connected() + "|" + c.autoRemovePlayed()
            + "|" + c.repeatOne() + "|" + c.partySbFlags() + "|" + c.playlist().version() + "|" + c.publicListVersion();
    }

    private final class Timeline extends AbstractSliderButton {
        private boolean dragging;
        private long pendingMs = -1;
        private long pendingUntil;

        Timeline(int x, int y, int w, int h) { super(x, y, w, h, Component.empty(), 0.0); updateMessage(); }

        void hold(long ms) { pendingMs = ms; pendingUntil = System.currentTimeMillis() + 1200; }

        private long shownPos() {
            if (dragging) return (long) (value * PlayerController.INSTANCE.duration());
            if (pendingMs >= 0 && System.currentTimeMillis() < pendingUntil) return pendingMs;
            return PlayerController.INSTANCE.position();
        }

        @Override protected void updateMessage() {
            setMessage(Component.literal(fmt(shownPos()) + " / " + fmt(PlayerController.INSTANCE.duration())));
        }

        @Override protected void applyValue() { updateMessage(); }

        @Override public void onClick(MouseButtonEvent e, boolean d) { dragging = true; super.onClick(e, d); }
        @Override protected void onDrag(MouseButtonEvent e, double dx, double dy) { dragging = true; super.onDrag(e, dx, dy); }
        @Override public void onRelease(MouseButtonEvent e) {
            super.onRelease(e);
            dragging = false;
            seek((long) (value * PlayerController.INSTANCE.duration()));
        }

        void refresh() {
            if (dragging) return;
            long dur = PlayerController.INSTANCE.duration();
            if (pendingMs >= 0 && System.currentTimeMillis() >= pendingUntil) pendingMs = -1;
            value = dur > 0 ? Math.max(0.0, Math.min(1.0, (double) shownPos() / dur)) : 0;
            updateMessage();
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026"; }
    private Component trackComponent(Track t, boolean current, PlayerController c) {
        String pre = current ? "\u266A " : "";
        if (t.requester().isEmpty()) return Component.literal(pre + trim(t.title(), 26));
        return Component.literal(pre + trim(t.title(), 16) + " \u2014 by " + trim(t.requester(), 8));
    }

    @Override
    public void removed() { ClientConfig.save(); }

    @Override
    public boolean isPauseScreen() { return false; }
}
