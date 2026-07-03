package de.zoeyvid.ytparty.party;

public enum PermissionLevel {
    LISTEN, INVITE, MANAGE;

    public byte id() { return (byte) ordinal(); }

    public boolean canInvite() { return ordinal() >= INVITE.ordinal(); }
    public boolean canManage() { return this == MANAGE; }

    public PermissionLevel cappedTo(PermissionLevel max) { return ordinal() <= max.ordinal() ? this : max; }

    public static PermissionLevel fromId(byte id) {
        PermissionLevel[] v = values();
        return id >= 0 && id < v.length ? v[id] : LISTEN;
    }

}
