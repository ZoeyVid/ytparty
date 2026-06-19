package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.playlist.Track;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class ClientConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ytparty-client.properties");
    private static volatile boolean loaded;
    private static byte sbFlags = de.zoeyvid.ytparty.audio.SponsorBlock.FLAG_ALL;

    private ClientConfig() {}

    public static byte sbFlags() { return sbFlags; }
    public static void setSbFlags(byte f) { sbFlags = f; save(); }

    public static synchronized void load() {
        Properties p = new Properties();
        if (Files.exists(PATH)) {
            try (InputStream in = Files.newInputStream(PATH)) { p.load(in); } catch (IOException ignored) {}
        }
        RelayClient.host = p.getProperty("relay.host", RelayClient.host);
        RelayClient.port = p.getProperty("relay.port", RelayClient.port);
        RelayClient.rememberPassword = Boolean.parseBoolean(p.getProperty("relay.remember-password", "true"));
        if (RelayClient.rememberPassword) RelayClient.password = p.getProperty("relay.password", "");
        PlayerController.INSTANCE.setVolume(parseInt(p.getProperty("volume", "100"), 100));
        sbFlags = (byte) parseInt(p.getProperty("sponsorblock.flags", String.valueOf(de.zoeyvid.ytparty.audio.SponsorBlock.FLAG_ALL)), de.zoeyvid.ytparty.audio.SponsorBlock.FLAG_ALL);
        if (Boolean.parseBoolean(p.getProperty("repeat", "false"))) PlayerController.INSTANCE.toggleRepeat();
        int n = parseInt(p.getProperty("track.count", "0"), 0);
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String uri = p.getProperty("track." + i + ".uri");
            if (uri != null) tracks.add(new Track(uri, p.getProperty("track." + i + ".title", ""), ""));
        }
        PlayerController.INSTANCE.loadSolo(tracks);
        loaded = true;
    }

    public static synchronized void save() {
        if (!loaded) return;
        Properties p = new Properties();
        p.setProperty("relay.host", RelayClient.host);
        p.setProperty("relay.port", RelayClient.port);
        p.setProperty("relay.remember-password", Boolean.toString(RelayClient.rememberPassword));
        if (RelayClient.rememberPassword) p.setProperty("relay.password", RelayClient.password);
        p.setProperty("volume", Integer.toString(PlayerController.INSTANCE.volume()));
        p.setProperty("sponsorblock.flags", Integer.toString(sbFlags));
        p.setProperty("repeat", Boolean.toString(PlayerController.INSTANCE.repeatOne()));
        List<Track> tracks = PlayerController.INSTANCE.soloTracks();
        p.setProperty("track.count", Integer.toString(tracks.size()));
        for (int i = 0; i < tracks.size(); i++) {
            p.setProperty("track." + i + ".uri", tracks.get(i).uri());
            p.setProperty("track." + i + ".title", tracks.get(i).title());
        }
        try (OutputStream out = Files.newOutputStream(PATH)) { p.store(out, "YT Party client config"); } catch (IOException ignored) {}
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; } }
}
