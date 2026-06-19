# CLAUDE.md — context for AI coding sessions

Guidance for an AI agent (Claude Code or similar) working in this repo. If your tool reads
`AGENTS.md` instead, point it here (same content). Read this first.

> This project is **deliberately vibecoded** (built end-to-end by Claude Opus 4.8). It is the owner's
> only such repo — every other project of theirs is hand-written and AI-free. Keep the bar high.

## What this is

YouTube audio in **Minecraft 26.1** with synchronized listening "parties". A client-side **Fabric mod**
fetches/decodes/plays YouTube audio itself (LavaPlayer → OpenAL) and keeps playlist order, current
track and pause state in sync per party. It is **fully standalone** (works solo with no backend) and
driven entirely through the GUI (key **J**, plus a button on the title screen).

Three backends speak the **same byte wire protocol** so the client drives them identically:

| Part | Path | Role |
|---|---|---|
| Mod | `mod/` | One source tree → **two jars**: a client+server *bundle* and a slim *server-only* jar. Package `de.zoeyvid.ytparty` (server logic) / `…ytparty` client classes under `src/client`. |
| Plugin | `plugin/` | Paper counterpart of the server side. Package `de.zoeyvid.ytparty`. |
| Relay | `relay/` | Standalone Go server for cross-server parties; **hybrid post-quantum encrypted**. Module `zoeyvid.de/ytparty-relay`. |

Channel id `ytparty:sync`. Docs live in `docs/` and are **always written in English**.

## Conventions

When changing code, match the conventions of the existing files (style, structure, naming). Repository
docs and identifiers are written in English.

## Build & run

Toolchain: **JDK 25** (records, `Math.clamp`, ML-KEM via SunJCE), **Gradle 9.4**, **Go 1.24+**
(stdlib `crypto/ecdh` + `crypto/mlkem`).

```sh
# Mod — both jars (bundle + slim server)
cd mod && gradle clean shadowJar serverJar
#   → build/libs/ytparty-0.1.0-bundle.jar   (client+server+LavaPlayer+natives, ~34 MB)
#   → build/libs/ytparty-0.1.0-server.jar   (server only, no audio libs, ~21 KB)

# Plugin
cd plugin && gradle clean build            # → build/libs/ytparty-plugin-0.1.0.jar

# Relay — static, stripped, cross-compiled
cd relay && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o ytparty-relay-linux-amd64 .
cd relay && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o ytparty-relay-linux-arm64 .
gofmt -l relay/ && go vet ./relay/...       # keep clean
```

Relay needs a PSK or it refuses to start:
`YTPARTY_RELAY_PASSWORD='…' ./ytparty-relay-linux-amd64` (printable ASCII only). Other env vars are in
`docs/CONFIGURATION.md`. CI is `.github/workflows/build.yml`.

**Sandbox note:** behind a TLS-intercepting egress proxy, Gradle needs the system truststore:
`export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"`
and pass `-Dorg.gradle.java.installations.paths=$JAVA_HOME`. Not needed on a normal network / in CI.

## Minecraft 26.1 / Fabric gotchas (hard-won — verify with `javap` before trusting memory)

- **26.1 is fully unobfuscated** (official Mojang names). The mod's `build.gradle` must **not** set
  `mappings loom.officialMojangMappings()`. Loom 1.15-SNAPSHOT, Loader 0.18.4, Fabric API
  `0.152.1+26.1.2`, Shadow 9.0.0. Paper: `io.papermc.paper:paper-api:26.1.2.build.+`.
- **`GuiGraphics` does not exist in 26.1.** Screens use the extracted render-state pipeline
  (`extractRenderState(GuiGraphicsExtractor,…)`), so you cannot draw custom primitives the old way.
  **Stick to widgets** (`StringWidget`, `Button`, `EditBox`, `AbstractSliderButton` via
  `addRenderableWidget`). Drag-and-drop in `PlaylistScreen` is implemented widget-only with a live
  preview, not custom rendering.
- **Mouse events use `MouseButtonEvent`** (a record: `.x()`, `.y()`, `.button()` 0=left, `.modifiers()`).
  Screen overrides: `mouseClicked(MouseButtonEvent, boolean)`, `mouseReleased(MouseButtonEvent)`,
  `mouseDragged(MouseButtonEvent, double dx, double dy)`, `mouseScrolled(double,double,double,double)`.
- `ServerPlayer` has **no `getServer()`** — get `MinecraftServer` via `ServerLifecycleEvents.SERVER_STARTED`.
- `GameProfile` is a **record** (`.name()`, not `.getName()`). Client identity:
  `Minecraft.getInstance().getUser().getName()` / `.getProfileId()`. Names: `getName().getString()`.
- `Identifier` (not `ResourceLocation`); `sendSystemMessage` (not `displayClientMessage`);
  `KeyMapping.Category.MISC`; payloads via `PayloadTypeRegistry.serverboundPlay()/clientboundPlay()`.
- `Minecraft.hasSingleplayerServer()` distinguishes integrated vs dedicated (used by the audio-stop rule).
- Title-screen button: Fabric `ScreenEvents.AFTER_INIT` + `Screens.getWidgets(screen)` when
  `screen instanceof TitleScreen`.

## Wire protocol (see `docs/PROTOCOL.md` for the full table)

Raw `DataOutputStream` bytes on `ytparty:sync`; the relay wraps the *same* payload in an encrypted
frame with a 4-byte big-endian length prefix. C2S ops 0–12 (12 = `SET_AUTOREMOVE`); S2C 0–4
(0 = `STATE`). `STATE` carries `autoRemovePlayed` (after `currentIndex`) and a per-member `duplicate`
flag (after `level`); only the relay ever sets `duplicate`, the MC backends always send `false`.
Levels: 0 LISTEN, 1 INVITE, 2 MANAGE. Volume is never synced (client-local).

**Auto-remove** (default on, synced): drop a track when it finishes or is skipped to the immediate
next index; a manual jump removes nothing. Backends apply this on `SET_INDEX` (target == `curIndex+1`
→ advance → remove current; anything else → jump).

**Relay-only** identity frame: `blob(name) ‖ blob(uuid) ‖ blob(token)` (each blob = u16 len + bytes);
ack = `{1} ‖ blob(token)`. First connect sends an empty token, relay issues one (TOFU, RAM-only,
expires). Relay keys *all* state by **token**, not UUID, so duplicate UUIDs are kept apart and flagged.
A new connection with an existing live token **replaces** it and keeps membership; a genuine
disconnect leaves the party.

## Crypto (relay only — see `docs/SECURITY.md`)

Hand-built from stdlib primitives — **not** TLS. Per connection: ephemeral **X25519 + ML-KEM-768**
hybrid KEM; the PBKDF2-derived PSK is also mixed into the session key, so a wrong PSK fails the GCM
tag. AES-256-GCM, 12-byte nonce `[dir|000|ctr8BE]`, direction-separated counters. Forward-secret,
replay-safe. Interop is byte-for-byte Java 25 ↔ Go 1.24. **PSK must be printable ASCII** (Java Latin-1
vs Go UTF-8 would diverge), enforced on both ends.

## Permissions (enforced server-side on all three backends)

LISTEN / INVITE / MANAGE. A party lives as long as ≥1 member has MANAGE; when the last manager leaves
it disbands and everyone else gets `LEFT`. Invites cap the granted level to the inviter's own. Public
parties: a manager toggles public + the join level. Random unguessable party ids. `ADD` caps:
≤500 tracks, uri ≤1000, title ≤200. The client UI only shows/hides controls accordingly.

## Status

Everything in the current scope is implemented across all backends and builds. Deferred features (with
effort estimates) are in `docs/planned-features.md`. When adding anything that touches the wire format,
change it in **all four** places (client mod, plugin, server mod, relay) and keep them byte-compatible.

## Things that can't be runtime-tested in a headless sandbox

Audio playback, the GUI (incl. drag-and-drop), the title-screen button, client persistence
(`config/ytparty-client.properties`), and the audio-stop-on-disconnect rule need a real client with a
sound device. Verify logic by reading the code; the owner tests these in-game.
