package de.zoeyvid.ytparty.server.net;

import de.zoeyvid.ytparty.server.party.Party;
import de.zoeyvid.ytparty.server.party.PermissionLevel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    public static final byte C2S_SET_TRACK = 8;
    public static final byte C2S_SET_PAUSED = 9;
    public static final byte C2S_SET_POSITION = 10;
    public static final byte C2S_SET_PUBLIC = 11;
    public static final byte C2S_SET_AUTOREMOVE = 12;
    public static final byte C2S_LIST_PUBLIC = 13;
    public static final byte C2S_REPORT_POSITION = 14;
    public static final byte C2S_SET_SPONSORBLOCK = 15;
    public static final byte C2S_SET_REPEAT = 16;
    public static final byte C2S_TRACK_ENDED = 17;
    public static final byte C2S_SET_PLAYLIST = 18;

    public static final byte S2C_STATE = 0;
    public static final byte S2C_INVITED = 1;
    public static final byte S2C_MESSAGE = 2;
    public static final byte S2C_LEFT = 3;
    public static final byte S2C_SEEK = 4;
    public static final byte S2C_PUBLIC_LIST = 5;

    private ServerProtocol() {}

    public record StateTemplate(byte[] bytes, int levelOffset) {}

    public static StateTemplate stateTemplate(Party party, Function<UUID, String> nameOf) {
        record Member(String name, PermissionLevel level) {}
        List<Member> members = new ArrayList<>(party.members.size());
        for (Map.Entry<UUID, PermissionLevel> e : party.members.entrySet()) members.add(new Member(nameOf.apply(e.getKey()), e.getValue()));
        members.sort(Comparator.comparingInt((Member m) -> -m.level().id()).thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bos)) {
            d.writeByte(S2C_STATE);
            d.writeUTF(party.id);
            int levelOffset = bos.size();
            d.writeByte(0);
            d.writeBoolean(party.isPublic);
            d.writeByte(party.publicJoinLevel.id());
            d.writeBoolean(party.paused);
            d.writeInt(party.currentIndex);
            d.writeBoolean(party.autoRemovePlayed);
            d.writeByte(party.sbFlags);
            d.writeBoolean(party.repeatOne);
            d.writeInt(party.generation);
            d.writeInt(party.tracks.size());
            for (Party.TrackRef t : party.tracks) { d.writeInt(t.id()); d.writeUTF(t.uri()); d.writeUTF(t.title()); d.writeUTF(t.requester()); }
            d.writeInt(members.size());
            for (Member m : members) { d.writeUTF(m.name()); d.writeByte(m.level().id()); }
            return new StateTemplate(bos.toByteArray(), levelOffset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] state(Party party, UUID viewer, Function<UUID, String> nameOf) {
        StateTemplate t = stateTemplate(party, nameOf);
        t.bytes()[t.levelOffset()] = party.level(viewer).id();
        return t.bytes();
    }

    public static byte[] left() { return write(d -> d.writeByte(S2C_LEFT)); }
    public static byte[] seek(long ms) { return write(d -> { d.writeByte(S2C_SEEK); d.writeLong(ms); }); }
    public static byte[] invited(String from, String partyId, PermissionLevel level) { return write(d -> { d.writeByte(S2C_INVITED); d.writeUTF(from); d.writeUTF(partyId); d.writeByte(level.id()); }); }
    public static byte[] message(String text) { return write(d -> { d.writeByte(S2C_MESSAGE); d.writeUTF(text); }); }
    public static byte[] publicList(List<Party> parties) { return write(d -> { d.writeByte(S2C_PUBLIC_LIST); d.writeInt(parties.size()); for (Party p : parties) { d.writeUTF(p.id); d.writeInt(p.members.size()); d.writeUTF(p.currentIndex >= 0 && p.currentIndex < p.tracks.size() ? p.tracks.get(p.currentIndex).title() : ""); } }); }

    private interface Body { void write(DataOutputStream d) throws IOException; }

    private static byte[] write(Body body) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bos)) {
            body.write(d);
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
