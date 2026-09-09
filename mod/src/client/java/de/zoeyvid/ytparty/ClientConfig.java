package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.audio.SponsorBlock;
import de.zoeyvid.ytparty.relay.RelayClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ytparty-client.properties");
    private static volatile boolean loaded;
    private static byte sbFlags = SponsorBlock.FLAG_ALL;
    private static boolean hudEnabled = true;
    private static int hudCorner = 1;
    private static boolean hudAlways = false;

    private ClientConfig() {}

    public static byte sbFlags() { return sbFlags; }
    public static void setSbFlags(byte f) { sbFlags = f; save(); }
    public static boolean hudEnabled() { return hudEnabled; }
    public static int hudCorner() { return hudCorner; }
    public static boolean hudAlways() { return hudAlways; }
    public static void setHudEnabled(boolean v) { hudEnabled = v; save(); }
    public static void setHudCorner(int v) { hudCorner = v; save(); }
    public static void setHudAlways(boolean v) { hudAlways = v; save(); }

    public static synchronized void load() {
        Properties p = new Properties();
        if (Files.exists(PATH)) {
            try (InputStream in = Files.newInputStream(PATH)) { p.load(in); } catch (IOException ignored) {}
        }
        RelayClient.host = p.getProperty("relay.host", RelayClient.host);
        RelayClient.port = p.getProperty("relay.port", RelayClient.port);
        RelayClient.rememberPassword = Boolean.parseBoolean(p.getProperty("relay.remember-password", "true"));
        if (RelayClient.rememberPassword) RelayClient.password = p.getProperty("relay.password", "");
        RelayClient.autoConnect = Boolean.parseBoolean(p.getProperty("relay.autoconnect", "false"));
        PlayerController.INSTANCE.setVolume(parseInt(p.getProperty("volume", "100"), 100));
        sbFlags = (byte) parseInt(p.getProperty("sponsorblock.flags", String.valueOf(SponsorBlock.FLAG_ALL)), SponsorBlock.FLAG_ALL);
        if (Boolean.parseBoolean(p.getProperty("repeat", "false"))) PlayerController.INSTANCE.toggleRepeat();
        if (!Boolean.parseBoolean(p.getProperty("autoremove", "true"))) PlayerController.INSTANCE.toggleAutoRemove();
        hudEnabled = Boolean.parseBoolean(p.getProperty("hud.enabled", "true"));
        hudCorner = parseInt(p.getProperty("hud.corner", "1"), 1);
        hudAlways = Boolean.parseBoolean(p.getProperty("hud.always", "false"));
        loaded = true;
    }

    public static synchronized void save() {
        if (!loaded) return;
        Properties p = new Properties();
        p.setProperty("relay.host", RelayClient.host);
        p.setProperty("relay.port", RelayClient.port);
        p.setProperty("relay.remember-password", Boolean.toString(RelayClient.rememberPassword));
        if (RelayClient.rememberPassword) p.setProperty("relay.password", RelayClient.password);
        p.setProperty("relay.autoconnect", Boolean.toString(RelayClient.autoConnect));
        p.setProperty("volume", Integer.toString(PlayerController.INSTANCE.volume()));
        p.setProperty("sponsorblock.flags", Integer.toString(sbFlags));
        p.setProperty("repeat", Boolean.toString(PlayerController.INSTANCE.repeatOne()));
        p.setProperty("autoremove", Boolean.toString(PlayerController.INSTANCE.autoRemovePlayed()));
        p.setProperty("hud.enabled", Boolean.toString(hudEnabled));
        p.setProperty("hud.corner", Integer.toString(hudCorner));
        p.setProperty("hud.always", Boolean.toString(hudAlways));
        try (OutputStream out = Files.newOutputStream(PATH)) { p.store(out, "YT Party client config"); } catch (IOException ignored) {}
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; } }
}
