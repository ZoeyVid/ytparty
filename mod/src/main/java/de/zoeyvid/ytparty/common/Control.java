package de.zoeyvid.ytparty.common;

import de.zoeyvid.ytparty.server.party.Party;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Control {
    public enum Emit { STATE, SEEK, NONE }

    public record Result(Emit emit, long seekMs) {}

    private static final Result STATE = new Result(Emit.STATE, 0);
    private static final Result NONE = new Result(Emit.NONE, 0);

    public static Result apply(Party p, byte op, DataInputStream d, String requester) throws IOException {
        int oldCur = p.curTrackId();
        int genBefore = p.generation;
        switch (op) {
            case Opcodes.C2S_ADD -> {
                String uri = d.readUTF();
                String title = cap(d.readUTF(), 200);
                if (uri.isEmpty() || uri.length() > 1000 || p.tracks.size() >= 500) return NONE;
                p.tracks.add(new Party.TrackRef(p.nextTrackId++, uri, title, requester));
                if (p.currentIndex < 0) p.currentIndex = 0;
            }
            case Opcodes.C2S_REMOVE -> {
                int i = p.indexOf(d.readInt());
                if (i >= 0) {
                    p.tracks.remove(i);
                    if (i < p.currentIndex) p.currentIndex--;
                    if (p.currentIndex >= p.tracks.size()) p.currentIndex = p.tracks.size() - 1;
                }
            }
            case Opcodes.C2S_MOVE -> {
                int from = p.indexOf(d.readInt()), to = d.readInt();
                if (from >= 0) {
                    to = Math.max(0, Math.min(to, p.tracks.size() - 1));
                    p.move(from, to);
                    if (from == p.currentIndex) p.currentIndex = to;
                    else if (from < p.currentIndex && to >= p.currentIndex) p.currentIndex--;
                    else if (from > p.currentIndex && to <= p.currentIndex) p.currentIndex++;
                }
            }
            case Opcodes.C2S_SET_TRACK -> {
                int i = p.indexOf(d.readInt());
                if (i < 0) return NONE;
                p.currentIndex = i;
                p.paused = false;
            }
            case Opcodes.C2S_TRACK_ENDED -> {
                int gen = d.readInt();
                if (gen != p.generation || p.currentIndex < 0) return NONE;
                if (p.repeatOne || (!p.autoRemovePlayed && p.tracks.size() == 1)) p.generation++;
                else if (p.autoRemovePlayed && p.currentIndex < p.tracks.size()) {
                    int cur = p.currentIndex;
                    p.tracks.remove(cur);
                    p.currentIndex = Math.min(cur, p.tracks.size() - 1);
                } else if (!p.tracks.isEmpty()) p.currentIndex = (p.currentIndex + 1) % p.tracks.size();
                else p.currentIndex = -1;
            }
            case Opcodes.C2S_SET_PLAYLIST -> {
                int n = d.readInt();
                if (n < 0 || n > 500) return NONE;
                List<Party.TrackRef> nt = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String uri = d.readUTF();
                    String title = cap(d.readUTF(), 200);
                    if (uri.isEmpty() || uri.length() > 1000) continue;
                    nt.add(new Party.TrackRef(p.nextTrackId++, uri, title, requester));
                }
                p.tracks.clear();
                p.tracks.addAll(nt);
                p.currentIndex = p.tracks.isEmpty() ? -1 : 0;
                p.paused = false;
            }
            case Opcodes.C2S_SET_PAUSED -> {
                boolean v = d.readBoolean();
                if (v && !p.paused) p.pausedSince = System.currentTimeMillis();
                else if (!v && p.paused) { p.pausedAccum += System.currentTimeMillis() - p.pausedSince; p.pausedSince = 0; }
                p.paused = v;
            }
            case Opcodes.C2S_SET_AUTOREMOVE -> p.autoRemovePlayed = d.readBoolean();
            case Opcodes.C2S_SET_SPONSORBLOCK -> p.sbFlags = (byte) (d.readByte() & 0x0F);
            case Opcodes.C2S_SET_REPEAT -> p.repeatOne = d.readBoolean();
            case Opcodes.C2S_SET_POSITION -> {
                long ms = d.readLong();
                p.generation++;
                p.anchor(ms);
                return new Result(Emit.SEEK, ms);
            }
            case Opcodes.C2S_REANCHOR -> { int gen = d.readInt(); long pos = d.readLong(); if (gen == p.generation && pos > p.elapsed()) p.anchor(pos); return NONE; }
            default -> { return NONE; }
        }
        if (p.curTrackId() != oldCur) p.generation++;
        if (p.generation != genBefore) p.anchor(0);
        return STATE;
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private Control() {}
}
