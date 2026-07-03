# YT Party

YouTube audio in Minecraft 26.2 with synchronized listening parties. A client-side Fabric mod
fetches, decodes and plays YouTube audio itself (playlist, play/pause, volume, seek) and keeps
the playlist order, current track and pause state in sync per "party". **Fully standalone:**
without a backend it works purely locally. Everything is controlled **through the UI** (key **J**).

> **Note — this project is "vibecoded".** It was built end-to-end by an AI (Claude Opus 4.8) from
> natural-language prompts, not hand-written by a human engineer. Keep that in mind: read the code and
> test it before relying on it. The quality reflects what Opus 4.8 produces at best — no more, no less.
>
> **This is the exception, not the rule.** All of my *other* projects are written by hand and are
> **AI-free — not vibecoded**. This repository is the only one built this way, and it is labelled as
> such on purpose.

| Part | Path | Role |
|------|------|------|
| Mod | `mod/` | **One jar for client and Fabric server.** Client: audio (LavaPlayer → OpenAL), GUI, playlist, sync. Server: party logic (own entrypoint, no audio). Also buildable as a slim **server jar without audio libraries**. |
| Plugin | `plugin/` | Paper counterpart of the server side. |
| Relay | `relay/` | Standalone, **encrypted** TCP server (Go, stdlib only, static binary) for worlds **without** a plugin/server-mod. |

Three backends, **one** byte protocol — the same client mod talks to all of them identically.
One backend is enough (or none → local only).

---

## For players

1. Install **Fabric** for Minecraft 26.2, then drop **Fabric API** and the
   **`ytparty-…-bundle.jar`** into your `mods/` folder.
2. In game, press **J** — the playlist menu opens.

**In the menu:**

- **Top:** a URL field + **Add**. Paste a YouTube link or type `ytsearch:query`.
- **Controls:** play/pause, skip, a **volume slider** (independent of Minecraft's master volume) and
  a clickable/draggable **timeline** for seeking.
- **Track list:** each row has ▶ (play), ▲▼ (reorder), ✕ (remove). The current track is marked ♪ and
  shows a ⏸/▶ toggle. Long playlists **scroll** with the mouse wheel (a counter shows the range).
- **Party:** **Create party** starts one, **Party…** opens management, **Leave** leaves it.
  **Relay** opens the relay connection (for worlds without a server backend).

**Managing a party (Party…):** managers can toggle `public` on/off, pick the join level, change member
levels, and **invite** via the name field (with a level). Invitees see a **Join** button next time they
open the menu. In a party everyone automatically follows whatever a manager plays/pauses/seeks. Three
levels: **LISTEN** (hear only), **INVITE** (+ invite), **MANAGE** (+ full control) — see
[`docs/PROTOCOL.md`](docs/PROTOCOL.md).

**Relay (no server backend):** in the menu choose **Relay** → enter host, port and password →
**Connect**. Party features then run over the relay instead of a Minecraft server.

The **J** key is a normal Minecraft keybind (category *Miscellaneous*) and can be **rebound** under
Options → Controls. It only fires in a world — there is currently no way to open the menu from the
main menu (a possible future feature).

### Who can be in a party together

A party lives entirely inside **one** backend; the relay and a server plugin/mod never bridge. So two
people share a party only if they share a backend. The relay is independent of Minecraft — it works
from singleplayer or from any server, as long as both connect to the **same** relay (same host/port/
password). A **singleplayer** integrated server is private to you (a party of one). Local playback
always works everywhere; only the *sync* needs a backend.

| Person A ↓ \ Person B → | Singleplayer (no relay) | Same server X (plugin/mod) | Different server Y | Server without backend | On the same relay |
|---|:--:|:--:|:--:|:--:|:--:|
| **Singleplayer (no relay)** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **Same server X (plugin/mod)** | ✗ | ✓ *(server X)* | ✗ | ✗ | ✗ |
| **Different server Y** | ✗ | ✗ | ✓ *(server Y)* | ✗ | ✗ |
| **Server without backend** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **On the same relay** | ✗ | ✗ | ✗ | ✗ | ✓ *(relay)* |

In words: two players can party **either** by being on the **same dedicated server** that runs the
plugin/server-mod (and neither using a relay — a connected relay overrides the server), **or** by both
connecting to the **same relay**. Concretely: two singleplayer users → only via a shared relay; a
plugin-server user + a singleplayer user → only if the server user *also* connects to that relay
(their server party is then replaced by the relay party).

**Different servers, shared relay:** yes — two people on *different* Paper/Fabric servers can listen
together if both connect to the same relay (each one's server backend is simply ignored while the relay
is connected). The relay doesn't care where you are in Minecraft.

**Leaving a world/server:** in a **server** party, disconnecting removes you from it (the party continues
for the others, or disbands if you were the last manager). A **relay** party is independent of Minecraft —
switching worlds or servers, or returning to the main menu, does **not** drop you from it; you stay until
you Leave or disconnect the relay.

To invite, type a name, or on a server pick from the online player list (**Invite from list…**, with a
search box for many players).

---

## Running a backend (one is enough)

| Backend | Install | Configuration |
|---|---|---|
| Fabric server | the **same `…-bundle.jar`** *or* the slim **`…-server.jar`** in `mods/` + Fabric API | none |
| Paper plugin | `ytparty-plugin-….jar` in `plugins/` | none |
| Relay | run the binary **or** Docker/Compose | environment only |

All options (with defaults and meaning) are in [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

Relay quickly via Compose (password e.g. from a `.env` file):

```
YTPARTY_RELAY_PASSWORD=… docker compose up -d   # compose.yaml + relay/Dockerfile
```

`YTPARTY_RELAY_PASSWORD` is required: if unset, the relay refuses to start and prints a randomly
generated example key to copy.

---

## Building

Mod + plugin need **JDK 25**, the relay needs **Go 1.26**. The Gradle wrapper is included.

```
cd mod    && ./gradlew shadowJar serverJar   # bundle.jar (~33 MB) + server.jar (~21 KB)
cd plugin && ./gradlew build                 # ytparty-plugin-0.1.0.jar
cd relay  && CGO_ENABLED=0 go build -ldflags "-s -w" -o ytparty-relay .   # static binary
```

CI workflows in [`.github/workflows/`](.github/workflows/): `mod.yml` and `plugin.yml` build the
jars, `relay.yml` builds and pushes the multi-arch Docker image (amd64 + arm64), plus lint workflows
(hadolint, shellcheck, codespell, JSON).

---

## Encryption & permissions

- Sync over the **plugin/server-mod** rides inside Minecraft's own game connection (already
  AES-encrypted in online mode). The **relay** encrypts its own socket with a hybrid
  **X25519 + ML-KEM-768** handshake (forward secrecy + post-quantum) and AES-256-GCM →
  [`docs/SECURITY.md`](docs/SECURITY.md).
- Permissions (LISTEN/INVITE/MANAGE) are enforced server-side → [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## More

| Doc | Contents |
|---|---|
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | Every config option, default and meaning |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Wire protocol (C2S/S2C) + permission system |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Security model, relay crypto, hardening, residual risks |
| [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) | All external dependencies |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Versions, build, 26.2 API, lessons, performance |
| [`relay/README.md`](relay/README.md) | Build & run the relay |

## Legal

Pulling YouTube audio this way violates the YouTube ToS. Common for private/community use, but it's
your responsibility.
