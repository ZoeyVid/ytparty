# Dependencies

What each component pulls in and why. Exact versions are pinned in the build files, not repeated here.

## Mod (`mod/`)

- **Fabric API** + **Fabric Loom** — the mod loader API and the Gradle plugin that maps Minecraft.
- **NewPipeExtractor** + **lavaplayer** — NewPipeExtractor resolves a YouTube URL to a direct audio
  stream, lavaplayer decodes it to PCM, in‑JVM. **Bundled** into the client jar via the Shadow plugin
  (that's why the client jar is large; the slim server jar has no audio and stays tiny).
- ARD Mediathek URLs are resolved against ARD's own API (no key needed) with the JDK HTTP client and
  the Gson that ships with Minecraft — no extra library.
- Crypto is JDK standard library only (ML‑KEM via the built‑in KEM API, AES‑GCM, PBKDF2) — no library.

## Plugin (`plugin/`)

- **Bukkit API** (`org.bukkit:bukkit`, `provided`) — supplied by the server, not bundled. Nothing else:
  the plugin uses only core Bukkit (events, plugin messaging, the built‑in YAML), so it needs no shaded
  libraries.

## Relay (`relay/`)

- **Go standard library only** — no third‑party modules. The container adds a small init (`tini`) on a
  minimal base image.
