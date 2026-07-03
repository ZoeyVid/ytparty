package de.zoeyvid.ytparty.net;

import de.zoeyvid.ytparty.PlayerController;
import de.zoeyvid.ytparty.common.Control;
import de.zoeyvid.ytparty.server.net.ServerProtocol;
import de.zoeyvid.ytparty.server.party.Party;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

public final class LocalSink implements PlayerController.Sink {
    public static final UUID SELF = new UUID(0, 0);

    public final Party party = new Party("", SELF);

    public void send(byte[] data) {
        try (DataInputStream d = SyncProtocol.reader(data)) {
            byte op = d.readByte();
            Control.Result r = Control.apply(party, op, d, "");
            switch (r.emit()) {
                case STATE -> ClientSync.dispatch(ServerProtocol.state(party, SELF, u -> ""));
                case SEEK -> ClientSync.dispatch(ServerProtocol.seek(r.seekMs(), party.generation));
                case NONE -> {}
            }
        } catch (IOException ignored) {}
    }
}
