package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.net.SyncPayload;
import de.zoeyvid.ytparty.server.net.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class YtPartyMain implements ModInitializer {
    public static final ServerSync SYNC = new ServerSync();

    @Override
    public void onInitialize() {
        try { PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC); } catch (Throwable ignored) {}
        try { PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC); } catch (Throwable ignored) {}

        ServerLifecycleEvents.SERVER_STARTED.register(SYNC::setServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> SYNC.setServer(null));
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> SYNC.onReceive(context.player(), payload.data()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SYNC.onDisconnect(handler.player, server));
    }

}
