package de.zoeyvid.ytparty.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import de.zoeyvid.ytparty.ClientConfig;
import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.audio.MusicPlayer;
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
    private final VideoPlayer player = new VideoPlayer(PlayerController.INSTANCE::position, () -> ClientSync.message("Couldn't load the video"));
    private DynamicTexture texture;
    private String url;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (Minecraft.getInstance().gui.screen() == null) draw(g);
    }

    public void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        PlayerController c = PlayerController.INSTANCE;
        MusicPlayer.Video largest = c.video(Integer.MAX_VALUE);
        float aspect = largest != null && largest.width() > 0 && largest.height() > 0 ? (float) largest.width() / largest.height() : 16f / 9;
        int boxWidth = g.guiWidth() * ClientConfig.videoSize() / 100, boxHeight = g.guiHeight() * ClientConfig.videoSize() / 100;
        int w = Math.min(boxWidth, Math.round(boxHeight * aspect)), h = Math.min(boxHeight, Math.round(boxWidth / aspect));
        int scale = mc.getWindow().getGuiScale(), pixelWidth = Math.max(2, w * scale & ~1), pixelHeight = Math.max(2, h * scale & ~1);
        MusicPlayer.Video video = ClientConfig.videoEnabled() && !mc.getWindow().isIconified() ? c.video(pixelHeight) : null;
        String next = video == null ? null : video.url();
        if (!Objects.equals(next, url)) { url = next; release(); }
        try { player.frame(url, video == null ? "" : video.headers(), video != null && video.hls(), c.live(), c.seeks(), pixelWidth, pixelHeight, this::upload); }
        catch (IOException e) { ClientConfig.setVideoEnabled(false); ClientSync.message("The video needs ffmpeg installed"); return; }
        if (texture == null) return;
        int x = (g.guiWidth() - w) * (ClientConfig.videoPosition() % 3) / 2, y = (g.guiHeight() - h) * (ClientConfig.videoPosition() / 3) / 2;
        g.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), x, y, x + w, y + h, 0, 1, 0, 1);
    }

    private void upload(byte[] pixels, int width, int height) {
        if (texture != null && (texture.getPixels().getWidth() != width || texture.getPixels().getHeight() != height)) release();
        if (texture == null) texture = new DynamicTexture(() -> "ytparty video", width, height, false);
        MemoryUtil.memByteBuffer(texture.getPixels().getPointer(), pixels.length).put(pixels);
        texture.upload();
    }

    private void release() {
        if (texture != null) texture.close();
        texture = null;
    }
}
