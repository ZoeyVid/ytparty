package de.zoeyvid.ytparty.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

public final class Parts {
    private ByteArrayOutputStream buf;
    private int expected;

    public byte[] add(DataInputStream d) throws IOException {
        int offset = d.readInt(), total = d.readInt(), n = d.available();
        if (offset == 0) { buf = total > 0 && total <= 1 << 21 ? new ByteArrayOutputStream() : null; expected = total; }
        if (buf == null || offset != buf.size() || total != expected || n > total - buf.size()) { buf = null; return null; }
        buf.writeBytes(d.readNBytes(n));
        if (buf.size() < total) return null;
        byte[] m = buf.toByteArray();
        buf = null;
        return m;
    }
}
