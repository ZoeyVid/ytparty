package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.net.SyncPayload;
import de.zoeyvid.ytparty.server.net.ServerSync;
import de.zoeyvid.ytparty.server.party.PermissionLevel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class YtPartyMain implements ModInitializer {
    public static final ServerSync SYNC = new ServerSync();

    @Override
    public void onInitialize() {
        try { PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC); } catch (Throwable ignored) {}
        try { PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC); } catch (Throwable ignored) {}

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) loadConfig();

        ServerLifecycleEvents.SERVER_STARTED.register(SYNC::setServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> SYNC.setServer(null));
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> SYNC.onReceive(context.player(), payload.data()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SYNC.onDisconnect(handler.player, server));
    }

    private void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("ytparty-server.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) { try (InputStream in = Files.newInputStream(file)) { props.load(in); } }
            else {
                props.setProperty("default-public", "false");
                props.setProperty("public-join-level", "listen");
                try (OutputStream out = Files.newOutputStream(file)) { props.store(out, "YT Party server defaults. public-join-level: listen | invite | manage"); }
            }
        } catch (IOException ignored) {}
        SYNC.manager().setDefaults(Boolean.parseBoolean(props.getProperty("default-public", "false")),
            PermissionLevel.fromName(props.getProperty("public-join-level", "listen")));
    }
}
