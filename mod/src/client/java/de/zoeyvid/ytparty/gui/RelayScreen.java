package de.zoeyvid.ytparty.gui;

import de.zoeyvid.ytparty.relay.RelayClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RelayScreen extends Screen {
    private EditBox hostField;
    private EditBox portField;
    private EditBox passField;
    private String lastStatus = "";

    public RelayScreen() { super(Component.literal("Relay")); }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int top = 30;
        lastStatus = RelayClient.INSTANCE.status().name() + RelayClient.INSTANCE.message();

        addRenderableWidget(new StringWidget(left, 12, 320, 12,
            Component.literal("Relay \u2014 " + RelayClient.INSTANCE.message()), this.font));

        addRenderableWidget(new StringWidget(left, top + 4, 60, 12, Component.literal("Host"), this.font));
        hostField = new EditBox(this.font, left + 64, top, 256, 20, Component.literal("host"));
        hostField.setMaxLength(255);
        hostField.setValue(RelayClient.host);
        hostField.setHint(Component.literal("relay.example.com"));
        addRenderableWidget(hostField);

        addRenderableWidget(new StringWidget(left, top + 30, 60, 12, Component.literal("Port"), this.font));
        portField = new EditBox(this.font, left + 64, top + 26, 256, 20, Component.literal("port"));
        portField.setValue(RelayClient.port);
        addRenderableWidget(portField);

        addRenderableWidget(new StringWidget(left, top + 56, 60, 12, Component.literal("Password"), this.font));
        passField = new EditBox(this.font, left + 64, top + 52, 256, 20, Component.literal("password"));
        passField.setMaxLength(255);
        passField.setValue(RelayClient.password);
        passField.addFormatter((s, idx) -> net.minecraft.util.FormattedCharSequence.forward("\u2022".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));
        addRenderableWidget(passField);

        addRenderableWidget(Button.builder(Component.literal("Remember password: " + (RelayClient.rememberPassword ? "ON" : "OFF")), b -> {
            RelayClient.rememberPassword = !RelayClient.rememberPassword;
            de.zoeyvid.ytparty.ClientConfig.save();
            rebuildWidgets();
        }).bounds(left, top + 78, 158, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Autoconnect: " + (RelayClient.autoConnect ? "ON" : "OFF")), b -> {
            RelayClient.autoConnect = !RelayClient.autoConnect;
            de.zoeyvid.ytparty.ClientConfig.save();
            rebuildWidgets();
        }).bounds(left + 162, top + 78, 158, 20).build());

        boolean busy = RelayClient.INSTANCE.connected() || RelayClient.INSTANCE.status() == RelayClient.Status.CONNECTING;
        if (busy) {
            addRenderableWidget(Button.builder(Component.literal("Disconnect"), b -> RelayClient.INSTANCE.disconnect()).bounds(left, top + 104, 320, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Connect"), b -> {
                RelayClient.host = hostField.getValue().trim();
                RelayClient.password = passField.getValue();
                int p = parsePort(portField.getValue().trim());
                RelayClient.port = String.valueOf(p);
                RelayClient.INSTANCE.connect(RelayClient.host, p, RelayClient.password);
            }).bounds(left, top + 104, 320, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> { de.zoeyvid.ytparty.ClientConfig.save(); this.minecraft.setScreenAndShow(new PlaylistScreen()); })
            .bounds(left, this.height - 28, 320, 20).build());
    }

    private static int parsePort(String s) {
        try { int p = Integer.parseInt(s); return p >= 1 && p <= 65535 ? p : 25599; } catch (NumberFormatException e) { return 25599; }
    }

    @Override
    public void tick() {
        RelayClient.host = hostField.getValue();
        RelayClient.port = portField.getValue();
        RelayClient.password = passField.getValue();
        String sig = RelayClient.INSTANCE.status().name() + RelayClient.INSTANCE.message();
        if (!sig.equals(lastStatus)) rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
