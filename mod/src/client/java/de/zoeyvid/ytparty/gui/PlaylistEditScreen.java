package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.playlist.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PlaylistEditScreen extends Screen {
    private String text;
    private boolean applying;

    public PlaylistEditScreen() { super(Component.literal("Edit playlist")); }

    @Override
    protected void init() {
        PlayerController c = PlayerController.INSTANCE;
        boolean editable = !c.hasParty() || c.canManage();
        int left = this.width / 2 - 160;
        if (text == null) { StringBuilder sb = new StringBuilder(); for (Track t : c.playlist().view()) sb.append(t.uri()).append('\n'); text = sb.toString(); }

        addRenderableWidget(new StringWidget(left, 12, 320, 12,
            Component.literal(applying ? "Resolving\u2026" : "One URL per line \u2014 Apply replaces the whole playlist"), this.font));

        MultiLineEditBox box = MultiLineEditBox.builder().setX(left).setY(32)
            .setPlaceholder(Component.literal("https://\u2026 (one URL per line)"))
            .build(this.font, 320, this.height - 92, Component.literal("Playlist URLs"));
        box.setCharacterLimit(200000);
        box.setLineLimit(500);
        box.setValue(text);
        box.setValueListener(v -> text = v);
        box.active = !applying;
        box.setTooltip(Tooltip.create(Component.literal("One URL per line \u2014 Apply replaces the whole playlist")));
        addRenderableWidget(box);

        Button apply = Button.builder(Component.literal(applying ? "Resolving\u2026" : "Apply"), b -> doApply())
            .tooltip(Tooltip.create(Component.literal("Replace the whole playlist with these URLs"))).bounds(left, this.height - 52, 157, 20).build();
        apply.active = editable && !applying;
        addRenderableWidget(apply);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.minecraft.setScreenAndShow(new PlaylistScreen()))
            .tooltip(Tooltip.create(Component.literal("Discard changes and go back"))).bounds(left + 163, this.height - 52, 157, 20).build());
    }

    private void doApply() {
        applying = true;
        Minecraft mc = this.minecraft;
        PlayerController.INSTANCE.applyPlaylistText(text, () -> mc.execute(() -> mc.setScreenAndShow(new PlaylistScreen())));
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
