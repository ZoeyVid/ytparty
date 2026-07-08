# CLAUDE.md — working notes

Context for anyone (human or AI) picking up this repo. Keep it about **architecture and invariants**;
concrete versions live in the build files, not here, so they don't rot.

> This project is **deliberately vibecoded** (built end-to-end by Claude Opus 4.8). It is the owner's
> only such repo — every other project of theirs is hand-written and AI-free. Keep the bar high.

## What it is

Synchronised YouTube listening in Minecraft. A Fabric **client mod** does the UI and the audio
(extract → decode with lavaplayer → OpenAL). Party state lives in a **backend**; there are three
interchangeable ones that all run the *same* logic:

- **relay** — standalone Go server, end‑to‑end encrypted, works across arbitrary servers
- **server mod** — the client jar's server side, for a Fabric server
- **plugin** — Bukkit plugin for Spigot / Paper / Folia

**Solo mode** is not a special case: the client runs an in‑process party (`LocalSink`) that goes
through the exact same logic, so solo and networked behave identically.

## Layout

- `mod/` — Fabric mod, split source sets: `src/client` (UI, audio, relay client) and `src/main`
  (server‑side party). `src/main/.../common/` (`Control`, `Opcodes`) is the shared brain used by the
  server mod **and** — compiled in — by the client's solo sink.
- `plugin/` — Bukkit plugin (pure `org.bukkit.*`, no Paper/Spigot API). Targets Java 8 + the Bukkit
  1.8 API so it loads on anything from 1.8 to current.
- `relay/` — Go, standard library only, shipped as a multi‑arch Docker image.

## The one hard part: the sync model

Position is not sent as a clock; it's an **anchor**. Each party stores an epoch/offset (`anchor`,
`elapsed`) plus a **generation** counter. Managers mutate state via ops; the backend re‑broadcasts a
`STATE` (and a `SEEK` carrying `generation` on position changes). Joiners re‑anchor to the party only
when the incoming generation matches **and** the position moves monotonically forward — this prevents
the stutter/rewind that a naïve last‑write‑wins would cause when several managers race. Repeat and
track‑end reuse the generation bump so a looped track re‑anchors cleanly.

## The protocol

One length‑prefixed binary channel (`ytparty:sync`). Opcodes are the single source of truth in
`common/Opcodes.java` (C2S 0–19, S2C 0–6). **`Control.apply` is shared** by all three backends, so a
new *behaviour* is almost always client‑side interpretation over existing ops — you rarely add an
opcode, and if you do it must land in all three. See [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Concurrency

Vanilla/Fabric and normal Bukkit are single‑threaded, so the server mod and plugin use plain maps.
**Folia** is the exception (real region‑thread parallelism): the plugin guards its shared state with a
single lock and declares `folia-supported`. The relay has its own mutex. `sendPluginMessage`/packet
sends are connection I/O and safe from any thread (verified against Folia's source — no tick‑thread
guard on messaging).

## Crypto (relay only)

PSK‑authenticated hybrid handshake: PBKDF2‑HMAC‑SHA256 over the shared password, mixed with an X25519
**and** an ML‑KEM (post‑quantum) exchange via HMAC‑SHA256 into a session key; traffic is AES‑256‑GCM
with directional, counter‑based nonces. Forward‑secret and replay‑safe. The Java and Go sides are
byte‑compatible. Details in [`docs/SECURITY.md`](docs/SECURITY.md).

## Building

Per‑component; versions are pinned in the build files (see [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)),
not duplicated here. Mod and plugin use Gradle wrappers; the relay is `go build` / Docker.

**Sandbox note:** behind a TLS‑intercepting egress proxy Gradle needs the system truststore —
`export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"`
and pass `-Dorg.gradle.java.installations.paths=<jdk>`. Not needed on a normal network or in CI.

## Minecraft/Fabric gotchas (verify with `javap` before trusting memory)

- Minecraft has been **unobfuscated since 26.1** (1.21.11 was the last obfuscated version) — official
  Mojang names throughout — so the mod's `build.gradle` must **not** set
  `mappings loom.officialMojangMappings()`, and there are no Yarn mappings to add.
- **No `GuiGraphics`** — screens go through the extracted render‑state pipeline. Draw with widgets
  (`StringWidget`, `Button`, `EditBox`, `AbstractSliderButton` via `addRenderableWidget`), not custom
  primitives; the playlist drag‑and‑drop is widget‑only with a live preview.
- Mouse events are `MouseButtonEvent` (a record). Networking uses the payload API
  (`serverboundPlay()`/`clientboundPlay()`), key binds use `KeyMappingHelper` + `KeyMapping.Category`.
