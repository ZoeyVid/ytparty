# Dependencies

What each component pulls in and why. Exact versions are pinned in the build files, not repeated here.

## Mod (`mod/`)

- **Fabric API** + **Fabric Loom** — the mod loader API and the Gradle plugin that maps Minecraft.
- **NewPipeExtractor** + **lavaplayer** — NewPipeExtractor resolves a YouTube URL to a direct audio
  stream, lavaplayer decodes it to PCM, in‑JVM. **Bundled** into the client jar via the Shadow plugin
  (that's why the client jar is large; the slim server jar has no audio and stays tiny).
- ARD Mediathek URLs are resolved against ARD's own API (no key needed) with the JDK HTTP client and
  the Gson that ships with Minecraft — no extra library.
- **ffmpeg** (optional, installed on the system, not bundled) — decodes the picture‑in‑picture video,
  YouTube livestreams and everything from other sites. The client starts the `ffmpeg` on the `PATH` as a
  child process and reads raw frames or PCM from it; the PCM goes through lavaplayer's pipeline like
  any other track. Without it, everything except these works.
- **yt-dlp** (optional, installed on the system, not bundled) — resolves URLs from other sites (and
  their livestreams and playlists) when *Other sites* is turned on. The client runs the `yt-dlp` on the
  `PATH` once per URL and reads its JSON; YouTube video and playlist URLs and ARD Mediathek video URLs
  never go through it.
- Crypto is JDK standard library only (ML‑KEM via the built‑in KEM API, AES‑GCM, PBKDF2) — no library.

## Plugin (`plugin/`)

- **Bukkit API** (`org.bukkit:bukkit`, `provided`) — supplied by the server, not bundled. Nothing else:
  the plugin uses only core Bukkit (events, plugin messaging, the built‑in YAML), so it needs no shaded
  libraries.

## Relay (`relay/`)

- **Go standard library only** — no third‑party modules. The container adds a small init (`tini`) on a
  minimal base image.
