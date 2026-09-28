package de.zoeyvid.ytparty.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.audio.VideoPlayer;
import de.zoeyvid.ytparty.net.ClientSync;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.util.Objects;

public final class VideoHud implements HudElement {
    private final VideoPlayer player = new VideoPlayer(PlayerController.INSTANCE::position);
    private DynamicTexture texture;
    private String url;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (Minecraft.getInstance().gui.screen() == null) draw(g);
    }

    public void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        PlayerController c = PlayerController.INSTANCE;
        String next = ClientConfig.videoEnabled() && !mc.getWindow().isIconified() ? c.video() : null;
        if (!Objects.equals(next, url)) {
            url = next;
            if (texture != null) { texture.close(); texture = null; }
        }
        byte[] frame;
        try { frame = player.frame(url, c.seeks()); }
        catch (IOException e) { ClientConfig.setVideoEnabled(false); ClientSync.message("The video needs ffmpeg installed"); return; }
        if (frame != null) {
            if (texture == null) texture = new DynamicTexture(() -> "ytparty video", VideoPlayer.WIDTH, VideoPlayer.HEIGHT, false);
            MemoryUtil.memByteBuffer(texture.getPixels().getPointer(), frame.length).put(frame);
            texture.upload();
        }
        if (texture == null) return;
        int w = Math.min(ClientConfig.videoSize(), g.guiWidth() - 8), h = w * 9 / 16;
        int corner = ClientConfig.hudCorner();
        int gap = ClientConfig.hudEnabled() && mc.level != null ? mc.font.lineHeight + 10 : 0;
        int x = (corner == 1 || corner == 3) ? g.guiWidth() - w - 4 : 4;
        int y = (corner == 2 || corner == 3) ? g.guiHeight() - h - 4 - gap : 4 + gap;
        g.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), x, y, x + w, y + h, 0, 1, 0, 1);
    }
}
