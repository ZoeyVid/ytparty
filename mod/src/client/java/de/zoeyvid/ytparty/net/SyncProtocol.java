package de.zoeyvid.ytparty.net;

import de.zoeyvid.ytparty.playlist.Track;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public final class SyncProtocol {
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
    public static final byte C2S_LIST_PUBLIC = 13;
    public static final byte C2S_REPORT_POSITION = 14;
    public static final byte C2S_SET_SPONSORBLOCK = 15;
    public static final byte C2S_SET_REPEAT = 16;

    public static final byte S2C_STATE = 0;
    public static final byte S2C_INVITED = 1;
    public static final byte S2C_MESSAGE = 2;
    public static final byte S2C_LEFT = 3;
    public static final byte S2C_SEEK = 4;
    public static final byte S2C_PUBLIC_LIST = 5;

    public record Member(String name, byte level, boolean duplicate) {}
    public record State(String partyId, byte myLevel, boolean isPublic, byte publicJoinLevel, boolean paused, int currentIndex, boolean autoRemovePlayed, byte sponsorBlockFlags, boolean repeatOne, List<Track> tracks, List<Member> members) {}

    private SyncProtocol() {}

    public static byte[] create() { return one(C2S_CREATE); }
    public static byte[] leave() { return one(C2S_LEAVE); }
    public static byte[] join(String partyId) { return write(C2S_JOIN, d -> d.writeUTF(partyId)); }
    public static byte[] invite(String name, byte level) { return write(C2S_INVITE, d -> { d.writeUTF(name); d.writeByte(level); }); }
    public static byte[] setLevel(String name, byte level) { return write(C2S_SET_LEVEL, d -> { d.writeUTF(name); d.writeByte(level); }); }
    public static byte[] setPublic(boolean isPublic, byte level) { return write(C2S_SET_PUBLIC, d -> { d.writeBoolean(isPublic); d.writeByte(level); }); }
    public static byte[] setAutoRemove(boolean on) { return write(C2S_SET_AUTOREMOVE, d -> d.writeBoolean(on)); }
    public static byte[] listPublic() { return one(C2S_LIST_PUBLIC); }

    public record PartyEntry(String id, int members, String currentTitle) {}
    public static byte[] add(String uri, String title) { return write(C2S_ADD, d -> { d.writeUTF(uri); d.writeUTF(title); }); }
    public static byte[] remove(int index) { return write(C2S_REMOVE, d -> d.writeInt(index)); }
    public static byte[] move(int from, int to) { return write(C2S_MOVE, d -> { d.writeInt(from); d.writeInt(to); }); }
    public static byte[] setIndex(int index) { return write(C2S_SET_INDEX, d -> d.writeInt(index)); }
    public static byte[] setPaused(boolean paused) { return write(C2S_SET_PAUSED, d -> d.writeBoolean(paused)); }
    public static byte[] setPosition(long ms) { return write(C2S_SET_POSITION, d -> d.writeLong(ms)); }
    public static byte[] reportPosition(long ms) { return write(C2S_REPORT_POSITION, d -> d.writeLong(ms)); }
    public static byte[] setSponsorBlock(byte flags) { return write(C2S_SET_SPONSORBLOCK, d -> d.writeByte(flags)); }
    public static byte[] setRepeat(boolean on) { return write(C2S_SET_REPEAT, d -> d.writeBoolean(on)); }

    public static State readState(DataInputStream d) throws IOException {
        String partyId = d.readUTF();
        byte myLevel = d.readByte();
        boolean isPublic = d.readBoolean();
        byte publicJoinLevel = d.readByte();
        boolean paused = d.readBoolean();
        int index = d.readInt();
        boolean autoRemovePlayed = d.readBoolean();
        byte sponsorBlockFlags = d.readByte();
        boolean repeatOne = d.readBoolean();
        int count = d.readInt();
        if (count < 0 || count > 500) throw new IOException("bad track count");
        List<Track> tracks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) tracks.add(new Track(d.readUTF(), d.readUTF(), d.readUTF()));
        int memberCount = d.readInt();
        if (memberCount < 0 || memberCount > 4096) throw new IOException("bad member count");
        List<Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) members.add(new Member(d.readUTF(), d.readByte(), d.readBoolean()));
        return new State(partyId, myLevel, isPublic, publicJoinLevel, paused, index, autoRemovePlayed, sponsorBlockFlags, repeatOne, tracks, members);
    }

    private static byte[] one(byte op) { return write(op, d -> {}); }

    private interface Body { void write(DataOutputStream d) throws IOException; }

    private static byte[] write(byte op, Body body) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bos)) {
            d.writeByte(op);
            body.write(d);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DataInputStream reader(byte[] data) { return new DataInputStream(new ByteArrayInputStream(data)); }
}
