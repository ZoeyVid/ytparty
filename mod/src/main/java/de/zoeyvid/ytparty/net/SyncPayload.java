package de.zoeyvid.ytparty.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ytparty", "sync"));

    public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeBytes(payload.data),
        buf -> {
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return new SyncPayload(b);
        }
    );

    @Override public Type<SyncPayload> type() { return TYPE; }
}
