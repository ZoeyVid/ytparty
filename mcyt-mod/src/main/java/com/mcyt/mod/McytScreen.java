package com.mcyt.mod;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class McytScreen extends Screen {
    
    private TextFieldWidget urlField;

    public McytScreen() {
        super(Text.literal("MCYT Audio Menu"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.urlField = new TextFieldWidget(this.textRenderer, centerX - 100, centerY - 60, 200, 20, Text.literal("YouTube URL"));
        this.addDrawableChild(this.urlField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add Track"), button -> {
            String url = urlField.getText();
            if (!url.isEmpty()) {
                NetworkSender.sendPlayTrack(url);
                urlField.setText("");
            }
        }).dimensions(centerX - 100, centerY - 30, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Play / Pause"), button -> {
            boolean isPaused = McytModClient.getInstance().getAudioEngine().isPaused();
            NetworkSender.sendPause(!isPaused);
        }).dimensions(centerX - 100, centerY, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reorder Shift"), button -> {
            List<String> currentPlaylist = new ArrayList<>(McytModClient.getInstance().getAudioEngine().getPlaylist());
            if (currentPlaylist.size() > 1) {
                String first = currentPlaylist.remove(0);
                currentPlaylist.add(first);
                NetworkSender.sendReorder(currentPlaylist);
            }
        }).dimensions(centerX - 100, centerY + 30, 200, 20).build());

        this.addDrawableChild(new SliderWidget(centerX - 100, centerY + 60, 200, 20, Text.literal("Volume"), McytModClient.getInstance().getAudioEngine().getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Volume: " + (int)(this.value * 100)));
            }

            @Override
            protected void applyValue() {
                McytModClient.getInstance().getAudioEngine().setVolume((float)(this.value * 100));
            }
        });
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
