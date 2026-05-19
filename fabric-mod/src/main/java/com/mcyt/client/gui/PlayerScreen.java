package com.mcyt.client.gui;

import com.mcyt.client.McytClientMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class PlayerScreen extends Screen {

    private TextFieldWidget urlField;
    private TextFieldWidget partyField;
    private ButtonWidget playButton;
    private ButtonWidget pauseButton;
    private ButtonWidget createPartyButton;
    private ButtonWidget joinPartyButton;
    private SliderWidget volumeSlider;
    
    private boolean isPaused = false;

    public PlayerScreen() {
        super(Text.literal("YouTube Player"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.urlField = new TextFieldWidget(this.textRenderer, centerX - 100, centerY - 60, 200, 20, Text.literal("YouTube URL"));
        this.urlField.setMaxLength(256);
        this.addDrawableChild(this.urlField);

        this.playButton = ButtonWidget.builder(Text.literal("Play URL"), button -> {
            String url = this.urlField.getText();
            if (!url.isEmpty()) {
                McytClientMod.AUDIO_ENGINE.playTrack(url);
                isPaused = false;
            }
        }).dimensions(centerX - 100, centerY - 35, 95, 20).build();
        this.addDrawableChild(this.playButton);

        this.pauseButton = ButtonWidget.builder(Text.literal("Pause/Resume"), button -> {
            isPaused = !isPaused;
            McytClientMod.AUDIO_ENGINE.setPaused(isPaused);
        }).dimensions(centerX + 5, centerY - 35, 95, 20).build();
        this.addDrawableChild(this.pauseButton);
        
        this.partyField = new TextFieldWidget(this.textRenderer, centerX - 100, centerY + 10, 200, 20, Text.literal("Party ID"));
        this.partyField.setMaxLength(32);
        this.addDrawableChild(this.partyField);

        this.createPartyButton = ButtonWidget.builder(Text.literal("Create Party"), button -> {
            String partyId = this.partyField.getText();
            if (!partyId.isEmpty()) {
                McytClientMod.NETWORK_HANDLER.sendCreate(partyId);
            }
        }).dimensions(centerX - 100, centerY + 35, 95, 20).build();
        this.addDrawableChild(this.createPartyButton);

        this.joinPartyButton = ButtonWidget.builder(Text.literal("Join Party"), button -> {
            String partyId = this.partyField.getText();
            if (!partyId.isEmpty()) {
                McytClientMod.NETWORK_HANDLER.sendJoin(partyId);
            }
        }).dimensions(centerX + 5, centerY + 35, 95, 20).build();
        this.addDrawableChild(this.joinPartyButton);
        
        this.volumeSlider = new SliderWidget(centerX - 100, centerY + 65, 200, 20, Text.literal("Volume"), 1.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Volume: " + (int)(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                McytClientMod.AUDIO_ENGINE.setVolume((float) this.value);
            }
        };
        this.addDrawableChild(this.volumeSlider);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        
        context.drawCenteredTextWithShadow(this.textRenderer, "YouTube Player", this.width / 2, this.height / 2 - 80, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, "Party Sync", this.width / 2, this.height / 2 - 5, 0xFFFFFF);
    }
}
