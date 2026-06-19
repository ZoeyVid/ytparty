package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.playlist.Track;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
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

        urlField = new EditBox(this.font, left, top, 250, 20, Component.literal("YouTube URL"));
        urlField.setMaxLength(2048);
        urlField.setHint(Component.literal("YouTube video or playlist URL"));
        urlField.setValue(savedUrl);
        addRenderableWidget(urlField);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
            String text = urlField.getValue().trim();
            if (!text.isEmpty()) { c.addUrl(text, null); urlField.setValue(""); savedUrl = ""; }
        }).bounds(left + 255, top, 65, 20).build());

        canEdit = !c.inParty() || c.canManage();
        addRenderableWidget(Button.builder(Component.literal(c.paused() ? "Play" : "Pause"), b -> c.togglePause())
            .bounds(left, top + 26, 80, 20).build()).active = canEdit;
        addRenderableWidget(Button.builder(Component.literal("Skip"), b -> c.next()).bounds(left + 84, top + 26, 80, 20).build()).active = canEdit;
        addRenderableWidget(new AbstractSliderButton(left + 168, top + 26, 152, 20, Component.literal("Vol " + c.volume()), c.volume() / 200.0) {
            @Override protected void updateMessage() { setMessage(Component.literal("Vol " + (int) (value * 200))); }
            @Override protected void applyValue() { c.setVolume((int) (value * 200)); }
        });

        timeline = new Timeline(left, top + 52, 320, 20);
        addRenderableWidget(timeline);

        addRenderableWidget(Button.builder(Component.literal("Auto-remove played: " + (c.autoRemovePlayed() ? "ON" : "OFF")), b -> c.toggleAutoRemove())
            .bounds(left, top + 78, 320, 20).build()).active = canEdit;

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
                addRenderableWidget(new StringWidget(left + 24, y + 6, 280, 12, Component.literal((i == marked ? "\u2261 " : "") + trim(order.get(i).title())), this.font));
                continue;
            }
            boolean current = i == c.currentIndex();
            addRenderableWidget(new StringWidget(left + 24, y + 6, 252, 12, Component.literal((current ? "\u266A " : "") + trim(order.get(i).title())), this.font));
            if (current) addRenderableWidget(Button.builder(Component.literal(c.paused() ? "\u25B6" : "\u23F8"), b -> c.togglePause()).bounds(left, y, 20, 20).build()).active = canEdit;
            else addRenderableWidget(Button.builder(Component.literal("\u25B6"), b -> c.playIndex(row)).bounds(left, y, 20, 20).build()).active = canEdit;
            addRenderableWidget(Button.builder(Component.literal("\u2715"), b -> c.removeAt(row)).bounds(left + 300, y, 20, 20).build()).active = canEdit;
        }
        if (tracks.size() > MAX_ROWS)
            addRenderableWidget(new StringWidget(left, listTop + MAX_ROWS * 22 + 2, 320, 12, Component.literal("\u2195 " + (scrollOffset + 1) + "\u2013" + end + " / " + tracks.size()), this.font));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean d) {
        if (super.mouseClicked(e, d)) return true;
        if (e.button() == 0 && canEdit) {
            int idx = rowAtForStart(e.y());
            if (idx >= 0) { dragFrom = idx; dragTo = idx; return true; }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragFrom >= 0) { dragTo = rowAtForDrop(e.y()); rebuildWidgets(); return true; }
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
            addRenderableWidget(Button.builder(Component.literal("Party\u2026"), b -> this.minecraft.setScreen(new PartyScreen())).bounds(left, y, 240, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Leave"), b -> c.leaveParty()).bounds(left + 244, y, 76, 20).build());
        } else if (c.pendingInviteId() != null) {
            addRenderableWidget(Button.builder(Component.literal("Create"), b -> c.createParty()).bounds(left, y, 110, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Join " + c.pendingInviteId()), b -> c.acceptInvite()).bounds(left + 114, y, 120, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Relay"), b -> this.minecraft.setScreen(new RelayScreen())).bounds(left + 238, y, 82, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Create party"), b -> c.createParty()).bounds(left, y, 235, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Relay"), b -> this.minecraft.setScreen(new RelayScreen())).bounds(left + 239, y, 81, 20).build());
        }
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
        StringBuilder sb = new StringBuilder();
        sb.append(c.inParty()).append('|').append(c.partyId()).append('|').append(c.paused())
          .append('|').append(c.currentIndex()).append('|').append(c.myLevel())
          .append('|').append(c.pendingInviteId()).append('|').append(RelayClient.INSTANCE.connected())
          .append('|').append(c.autoRemovePlayed());
        for (Track t : c.playlist().view()) sb.append('|').append(t.uri());
        return sb.toString();
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

    private String trim(String s) { return s.length() <= 26 ? s : s.substring(0, 25) + "\u2026"; }

    @Override
    public boolean isPauseScreen() { return false; }
}
