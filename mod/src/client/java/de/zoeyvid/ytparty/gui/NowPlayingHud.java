package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.playlist.Track;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class NowPlayingHud implements HudElement {
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!ClientConfig.hudEnabled()) return;
        PlayerController c = PlayerController.INSTANCE;
        Track t = c.currentTrack();
        if (t == null) return;
        if (!ClientConfig.hudAlways() && System.currentTimeMillis() - c.trackChangedAt() > 4000) return;
        Font font = Minecraft.getInstance().font;
        String label = (c.paused() ? "\u23F8 " : "\u266A ") + trim(t.title(), 32);
        int boxW = font.width(label) + 8;
        int boxH = font.lineHeight + 8;
        int corner = ClientConfig.hudCorner();
        int x = (corner == 1 || corner == 3) ? g.guiWidth() - boxW - 4 : 4;
        int y = (corner == 2 || corner == 3) ? g.guiHeight() - boxH - 4 : 4;
        g.fill(x, y, x + boxW, y + boxH, 0xC0000000);
        g.text(font, Component.literal(label), x + 4, y + 4, 0xFFFFFFFF, true);
    }

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026"; }
}
