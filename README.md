# YT Party

Synchronised YouTube and ARD Mediathek listening inside Minecraft. Everyone in a *party* hears the
same track at the same position — play/pause, seek, skip and playlist edits stay in lockstep. Audio is
decoded and played entirely client‑side; nothing but small control messages travels over the network.

> **Note — this project is vibecoded.** It was built end-to-end by an AI (Claude Opus) from
> natural-language prompts, not hand-written by a human engineer. Keep that in mind: read the code and
> test it before relying on it. The quality reflects what Opus produces at best — no more, no less.
>
> **This is the exception, not the rule.** All of my *other* projects are written by hand and are
> **AI-free — not vibecoded**. This repository is the only one built this way, and it is labelled as
> such on purpose.

## Components

| Component | What it is | Who needs it |
|---|---|---|
| **Client mod** | Fabric mod: the in‑game UI and the audio player (extract → decode → OpenAL) | every listener |
| **Relay** | standalone, end‑to‑end‑encrypted server that hosts party state across *any* servers | run one, or use a public one |
| **Server mod** | a separate, small `-slim.jar` (party logic) for a Fabric server; it also works as the client mod, but then needs ffmpeg for all audio | server owner (optional) |
| **Plugin** | Bukkit plugin for Spigot / Paper / Folia servers | server owner (optional) |

You only ever need the **client mod**. It works **solo** out of the box (a private, in‑process party).
To listen *together* you point it at one **backend** — a relay, a Fabric server running the server
mod, or a Bukkit server running the plugin. All three speak the same protocol and run the same party
logic, so the experience is identical whichever you use.

## Who can be in a party together

A party lives entirely inside **one** backend; the relay and a server plugin/mod never bridge. So two
people share a party only if they share a backend. The relay is independent of Minecraft — it works
from singleplayer or from any server, as long as both connect to the **same** relay (same host / port /
password). A **singleplayer** world is private to you (a party of one). Local playback always works
everywhere; only the *sync* needs a backend.

| Person A ↓ \\ B → | Singleplayer (no relay) | Server X (plugin/mod) | Server Y (plugin/mod) | Server without backend | On the same relay |
|---|:--:|:--:|:--:|:--:|:--:|
| **Singleplayer (no relay)** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **Server X (plugin/mod)** | ✗ | ✓ *(server X)* | ✗ | ✗ | ✗ |
| **Server Y (plugin/mod)** | ✗ | ✗ | ✓ *(server Y)* | ✗ | ✗ |
| **Server without backend** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **On the same relay** | ✗ | ✗ | ✗ | ✗ | ✓ *(relay)* |

In words: party up **either** by being on the **same** dedicated server that runs the plugin/server‑mod
(and neither side using a relay — a connected relay overrides the server), **or** by both connecting to
the **same relay**. Two people on *different* Paper/Fabric servers can listen together via a shared
relay; each one's server backend is simply ignored while the relay is connected.

## Quick start

1. **Install the client mod** (needs Fabric API) in your `mods/` folder: the `-bundle.jar`, or the much
   smaller `-slim.jar` if [ffmpeg](https://ffmpeg.org) is installed, which it then uses for all audio
   (install only one of the two jars; with both, Fabric loads either one). Open the party UI with the
   keybind (rebindable under Options → Controls) and add a YouTube or ARD Mediathek video URL — that
   already works solo. A playlist URL adds its first video. ARD Mediathek videos that need a login can't
   be added, age‑restricted (FSK) ones only late in the evening (as on the website: FSK 16 from 22:00,
   FSK 18 from 23:00), and some are only available in Germany or the DACH region. With
   [ffmpeg](https://ffmpeg.org) installed (on your `PATH` or in a
   [usual install folder](docs/DEPENDENCIES.md)), the *Settings* screen can also show the video
   as a small picture‑in‑picture window, synced to the audio (toggle key `K`). ffmpeg also plays YouTube
   livestreams (audio only); each listener hears them at their own live point, so they aren't synced or
   seekable. A short dropout is bridged by reconnecting; after a longer one the stream is loaded once
   more, and if that fails too you get an error. With [yt-dlp](https://github.com/yt-dlp/yt-dlp)
   installed as well, URLs from the other sites yt-dlp supports play too, but only from the sites you
   list under *Settings* → *Allowed sites* (none by default; e.g. `https://www.zdf.de`). Their
   livestreams and internet radios (Icecast, Shoutcast) play like YouTube livestreams; a file whose
   length can't be read is shown as LIVE too (not seekable or synced, no video), but it ends normally.
2. **To sync with others**, either:
   - **Relay:** enter host / port / password in the in‑game *Relay* screen and connect. Run your own
     with Docker (`relay/compose.yaml` + `relay/Dockerfile`, image `ghcr.io/zoeyvid/ytparty`) — see
     [`relay/README.md`](relay/README.md).
   - **Server backend:** join a Fabric server with the server mod, or a Bukkit server with the plugin.
     No client setup needed — the party UI just works there.

## Build

Each component builds independently; exact tool and library versions are pinned in the build files
(`mod/gradle.properties`, `mod/build.gradle`, `plugin/build.gradle`, `relay/go.mod`,
`relay/Dockerfile`) rather than repeated here. See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## Docs

| File | Contents |
|---|---|
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | building, project layout, per‑component toolchain |
| [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) | what each component depends on and why |
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | relay settings and the in‑game client settings |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | the wire protocol and the synchronisation model |
| [`docs/SECURITY.md`](docs/SECURITY.md) | threat model and the relay's encryption |
| [`docs/PLANNED.md`](docs/PLANNED.md) | ideas not yet built |

## License

See [`COPYING`](COPYING).
