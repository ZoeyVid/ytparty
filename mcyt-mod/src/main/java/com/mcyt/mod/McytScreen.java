package com.mcyt.mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class McytScreen extends Screen {
    
    private EditBox urlField;

    public McytScreen() {
        super(Component.literal("MCYT Audio Menu"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.urlField = new EditBox(this.font, centerX - 100, centerY - 60, 200, 20, Component.literal("YouTube URL"));
        this.addRenderableWidget(this.urlField);

        this.addRenderableWidget(Button.builder(Component.literal("Add Track"), button -> {
            String url = urlField.getValue();
            if (!url.isEmpty()) {
                NetworkSender.sendPlayTrack(url);
                urlField.setValue("");
            }
        }).bounds(centerX - 100, centerY - 30, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Play / Pause"), button -> {
            boolean isPaused = McytModClient.getInstance().getAudioEngine().isPaused();
            NetworkSender.sendPause(!isPaused);
        }).bounds(centerX - 100, centerY, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reorder Shift"), button -> {
            List<String> currentPlaylist = new ArrayList<>(McytModClient.getInstance().getAudioEngine().getPlaylist());
            if (currentPlaylist.size() > 1) {
                String first = currentPlaylist.remove(0);
                currentPlaylist.add(first);
                NetworkSender.sendReorder(currentPlaylist);
            }
        }).bounds(centerX - 100, centerY + 30, 200, 20).build());

        this.addRenderableWidget(new AbstractSliderButton(centerX - 100, centerY + 60, 200, 20, Component.literal("Volume"), McytModClient.getInstance().getAudioEngine().getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal("Volume: " + (int)(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                McytModClient.getInstance().getAudioEngine().setVolume((float) (this.value * 100.0));
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
