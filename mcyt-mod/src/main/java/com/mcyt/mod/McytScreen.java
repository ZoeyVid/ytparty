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
        super(Text.literal("MCYT Audio Player"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        // URL input
        urlField = new TextFieldWidget(textRenderer, centerX - 100, centerY - 60, 200, 20, Text.literal("YouTube URL"));
        this.addDrawableChild(urlField);

        // Add track button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add Track"), button -> {
            if (!urlField.getText().isEmpty()) {
                NetworkSender.sendPlayTrack(urlField.getText());
                urlField.setText("");
            }
        }).dimensions(centerX - 100, centerY - 30, 200, 20).build());

        // Pause/Play Toggle
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Play / Pause"), button -> {
            boolean currentPause = McytModClient.getInstance().getAudioEngine().isPaused();
            NetworkSender.sendPause(!currentPause);
        }).dimensions(centerX - 100, centerY, 95, 20).build());
        
        // Reorder Dummy Button (moves top item to bottom to demonstrate reordering)
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reorder Shift"), button -> {
            List<String> current = new ArrayList<>(McytModClient.getInstance().getAudioEngine().getPlaylist());
            if (current.size() > 1) {
                String first = current.remove(0);
                current.add(first);
                NetworkSender.sendReorder(current);
            }
        }).dimensions(centerX + 5, centerY, 95, 20).build());

        // Volume Slider
        this.addDrawableChild(new SliderWidget(centerX - 100, centerY + 30, 200, 20, Text.literal("Volume"), McytModClient.getInstance().getAudioEngine().getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Volume: " + (int)(this.value * 100) + "%"));
            }
            @Override
            protected void applyValue() {
                McytModClient.getInstance().getAudioEngine().setVolume((float) (this.value * 100.0));
            }
        });
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
