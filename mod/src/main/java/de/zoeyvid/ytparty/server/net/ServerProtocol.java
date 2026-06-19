package de.zoeyvid.ytparty.server.net;

import de.zoeyvid.ytparty.server.party.Party;
import de.zoeyvid.ytparty.server.party.PermissionLevel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class ServerProtocol {
    public static final byte C2S_CREATE = 0;
    public static final byte C2S_JOIN = 1;
    public static final byte C2S_LEAVE = 2;
    public static final byte C2S_INVITE = 3;
    public static final byte C2S_SET_LEVEL = 4;
    public static final byte C2S_ADD = 5;
    public static final byte C2S_REMOVE = 6;
    public static final byte C2S_MOVE = 7;
    public static final byte C2S_SET_INDEX = 8;
    public static final byte C2S_SET_PAUSED = 9;
    public static final byte C2S_SET_POSITION = 10;
    public static final byte C2S_SET_PUBLIC = 11;
    public static final byte C2S_SET_AUTOREMOVE = 12;

    public static final byte S2C_STATE = 0;
    public static final byte S2C_INVITED = 1;
    public static final byte S2C_MESSAGE = 2;
    public static final byte S2C_LEFT = 3;
    public static final byte S2C_SEEK = 4;

    private ServerProtocol() {}

    public static byte[] state(Party party, UUID viewer, Function<UUID, String> nameOf) {
        return write(d -> {
            d.writeByte(S2C_STATE);
            d.writeUTF(party.id);
            d.writeByte(party.level(viewer).id());
            d.writeBoolean(party.isPublic);
            d.writeByte(party.publicJoinLevel.id());
            d.writeBoolean(party.paused);
            d.writeInt(party.currentIndex);
            d.writeBoolean(party.autoRemovePlayed);
            d.writeInt(party.tracks.size());
            for (Party.TrackRef t : party.tracks) { d.writeUTF(t.uri()); d.writeUTF(t.title()); }
            d.writeInt(party.members.size());
            for (Map.Entry<UUID, PermissionLevel> e : party.members.entrySet()) {
                d.writeUTF(nameOf.apply(e.getKey()));
                d.writeByte(e.getValue().id());
                d.writeBoolean(false);
            }
        });
    }

    public static byte[] left() { return write(d -> d.writeByte(S2C_LEFT)); }
    public static byte[] seek(long ms) { return write(d -> { d.writeByte(S2C_SEEK); d.writeLong(ms); }); }
    public static byte[] invited(String from, String partyId, PermissionLevel level) { return write(d -> { d.writeByte(S2C_INVITED); d.writeUTF(from); d.writeUTF(partyId); d.writeByte(level.id()); }); }
    public static byte[] message(String text) { return write(d -> { d.writeByte(S2C_MESSAGE); d.writeUTF(text); }); }

    private interface Body { void write(DataOutputStream d) throws IOException; }

    private static byte[] write(Body body) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bos)) {
            body.write(d);
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
