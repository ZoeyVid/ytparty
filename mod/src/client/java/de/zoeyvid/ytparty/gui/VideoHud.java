package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.audio.VideoPlayer;
import de.zoeyvid.ytparty.net.ClientSync;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.util.Objects;

public final class VideoHud implements HudElement {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("ytparty", "video");
    private static final int W = VideoPlayer.WIDTH / 2, H = VideoPlayer.HEIGHT / 2;

    private final VideoPlayer player = new VideoPlayer(PlayerController.INSTANCE::position);
    private DynamicTexture texture;
    private String url;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        PlayerController c = PlayerController.INSTANCE;
        String next = ClientConfig.videoEnabled() ? c.video() : null;
        if (!Objects.equals(next, url)) {
            url = next;
            if (texture != null) { Minecraft.getInstance().getTextureManager().release(ID); texture = null; }
        }
        byte[] frame;
        try { frame = player.frame(url, c.seeks()); }
        catch (IOException e) { ClientConfig.setVideoEnabled(false); ClientSync.message("The video needs ffmpeg installed"); return; }
        if (frame != null) {
            if (texture == null) {
                texture = new DynamicTexture(() -> "ytparty video", VideoPlayer.WIDTH, VideoPlayer.HEIGHT, false);
                Minecraft.getInstance().getTextureManager().register(ID, texture);
            }
            MemoryUtil.memByteBuffer(texture.getPixels().getPointer(), frame.length).put(frame);
            texture.upload();
        }
        if (texture == null) return;
        int corner = ClientConfig.hudCorner();
        int gap = ClientConfig.hudEnabled() ? Minecraft.getInstance().font.lineHeight + 10 : 0;
        int x = (corner == 1 || corner == 3) ? g.guiWidth() - W - 4 : 4;
        int y = (corner == 2 || corner == 3) ? g.guiHeight() - H - 4 - gap : 4 + gap;
        g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0, 0, W, H, VideoPlayer.WIDTH, VideoPlayer.HEIGHT, VideoPlayer.WIDTH, VideoPlayer.HEIGHT);
    }
}
