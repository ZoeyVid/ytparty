# Dependencies

What each component pulls in and why. Exact versions are pinned in the build files, not repeated here.

## Mod (`mod/`)

- **Fabric API** + **Fabric Loom** — the mod loader API and the Gradle plugin that maps Minecraft.
- **NewPipeExtractor** + **lavaplayer** — NewPipeExtractor resolves a YouTube URL to a direct audio
  stream, lavaplayer decodes it to PCM, in‑JVM (a livestream resolves to an HLS or DASH URL that ffmpeg
  plays instead); it also plays the media files yt-dlp resolves for other sites. **Bundled** into the
  client jar via the Shadow plugin (that's why the client jar is large). The slim jar, which also works
  as the client, only carries NewPipeExtractor and lavaplayer's core (no natives, HTTP client or
  Jackson): it detects that from a `slim` marker file and plays every track through ffmpeg, whose PCM
  already has the output format, so lavaplayer's pipeline needs no natives.
- ARD Mediathek URLs are resolved against ARD's own API (no key needed) with the JDK HTTP client and
  the Gson that ships with Minecraft — no extra library.
- **ffmpeg** (optional, installed on the system, not bundled) — decodes the picture‑in‑picture video,
  YouTube livestreams and whatever lavaplayer can't play from other sites (livestreams, HLS, OGG, FLAC,
  more than two channels, files of unknown length, servers that ignore range requests, or when
  lavaplayer fails, yields no audio or breaks off). The client starts `ffmpeg` as a child process and
  reads raw frames or PCM from it; the PCM goes through lavaplayer's pipeline like any other track.
  Without it, everything except these works, and nothing from other sites plays: they always need
  both ffmpeg and yt-dlp; with the slim jar as the client, nothing plays without it.
- **yt-dlp** (optional, installed on the system, not bundled) — resolves URLs from other sites (and
  their livestreams and playlists) listed under *Allowed sites*. The client runs `yt-dlp` once per
  URL and reads its JSON (after a failure also `yt-dlp --version`, until that has worked once, so a
  broken installation is reported with its error); YouTube video and playlist URLs and ARD Mediathek
  video URLs never go through it, but other YouTube and ARD Mediathek links (channels, live or show
  pages) do.
- Both programs are looked for on the `PATH` first, then in the usual install folders the game's `PATH`
  often lacks (macOS apps started from the Dock or Finder, a launcher already running during the
  install): `/opt/homebrew/bin`, `/usr/local/bin` and `/opt/local/bin` on macOS; `/usr/local/bin`,
  `~/.local/bin` and `/snap/bin` on Linux; `%LOCALAPPDATA%\Microsoft\WinGet\Links`,
  `%USERPROFILE%\scoop\shims` and `%ChocolateyInstall%\bin` on Windows. The path found is
  remembered and looked up again when the program can't be started any more; a missing program is
  named in the error. A game started from a Flatpak launcher can't see programs installed on the host.
- Crypto is JDK standard library only (X25519, ML‑KEM via the built‑in KEM API, AES‑GCM, HMAC‑SHA256) — no library.

## Plugin (`plugin/`)

- **Bukkit API** (`org.bukkit:bukkit`, `provided`) — supplied by the server, not bundled. Nothing else:
  the plugin uses only core Bukkit (events, plugin messaging, the built‑in YAML), so it needs no shaded
  libraries.

## Relay (`relay/`)

- **Go standard library only** — no third‑party modules. The container adds a small init (`tini`) on a
  minimal base image.
