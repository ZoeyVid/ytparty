package de.zoeyvid.ytparty.common;

public final class Opcodes {
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
    public static final byte C2S_REANCHOR = 14;
    public static final byte C2S_SET_SPONSORBLOCK = 15;
    public static final byte C2S_SET_REPEAT = 16;
    public static final byte C2S_TRACK_ENDED = 17;
    public static final byte C2S_SET_PLAYLIST = 18;
    public static final byte C2S_LIST_PLAYERS = 19;

    public static final byte S2C_STATE = 0;
    public static final byte S2C_INVITED = 1;
    public static final byte S2C_MESSAGE = 2;
    public static final byte S2C_LEFT = 3;
    public static final byte S2C_SEEK = 4;
    public static final byte S2C_PUBLIC_LIST = 5;
    public static final byte S2C_PLAYER_LIST = 6;

    private Opcodes() {}
}
