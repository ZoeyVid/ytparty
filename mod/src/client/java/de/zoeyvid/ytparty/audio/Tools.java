package de.zoeyvid.ytparty.audio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class Tools {
    private static final Map<String, String> FOUND = new ConcurrentHashMap<>();

    private Tools() {}

    static boolean installed(String name) { return FOUND.computeIfAbsent(name, tool -> find(tool).findFirst().orElse(null)) != null; }

    static Process start(ProcessBuilder builder) throws IOException {
        String name = builder.command().getFirst();
        IOException failure = null;
        for (Iterator<String> paths = Stream.concat(Stream.ofNullable(FOUND.get(name)), find(name)).distinct().iterator(); paths.hasNext(); ) {
            String path = paths.next();
            builder.command().set(0, path);
            try { Process process = builder.start(); FOUND.put(name, path); return process; }
            catch (IOException e) { FOUND.remove(name, path); failure = e; }
        }
        throw failure != null ? failure : new IOException(name + " isn't installed");
    }

    private static Stream<String> find(String name) {
        String os = System.getProperty("os.name"), path = System.getenv("PATH");
        boolean windows = os.startsWith("Windows");
        return Stream.concat(path != null ? Stream.of(path.split(File.pathSeparator)) : windows ? Stream.empty() : Stream.of("/bin", "/usr/bin"),
                windows ? Stream.of(System.getenv("LOCALAPPDATA") + "\\Microsoft\\WinGet\\Links", System.getenv("USERPROFILE") + "\\scoop\\shims", System.getenv("ChocolateyInstall") + "\\bin")
                    : os.startsWith("Mac") ? Stream.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin") : Stream.of("/usr/local/bin", System.getProperty("user.home") + "/.local/bin", "/snap/bin"))
            .<Path>mapMulti((dir, files) -> { try { files.accept(Path.of(dir.replace("\"", ""), windows ? name + ".exe" : name)); } catch (InvalidPathException ignored) {} })
            .filter(file -> file.isAbsolute() && Files.isRegularFile(file) && Files.isExecutable(file)).map(Path::toString);
    }
}
