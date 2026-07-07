# Development

## Verified versions

| Thing | Value |
|-------|-------|
| Minecraft | 26.2 (Java 25, `year.drop.hotfix` scheme) |
| Mappings | official Mojang mappings (Yarn is gone for 26.1) |
| Fabric | Loom 1.17.13, Gradle 9.6.1, Loader 0.19.3, API `0.154.0+26.2` |
| Bukkit (plugin) | `org.bukkit:bukkit:1.8-R0.1-SNAPSHOT` (Java 8, Spigot repo) |
| LavaPlayer | `dev.arbjerg:lavaplayer:2.2.7` (Maven Central) |
| YouTube source | `dev.lavalink.youtube:common:1.18.1` (`https://maven.lavalink.dev/releases`) |
| Relay | Go 1.26 (stdlib only) |
| Packages | `de.zoeyvid.ytparty` (mod + plugin), Go module `zoeyvid.de/ytparty-relay` |

## Building

The mod needs **JDK 25**; the plugin builds with a **JDK 8** toolchain against the Bukkit 1.8 API; the relay needs Go 1.26. The Gradle wrapper (9.6.1) is included. (Gradle 9 itself needs JDK 17+ to run, so the plugin's CI installs JDK 8 for the toolchain plus JDK 25 to run Gradle — same 25 the mod uses.)

```
cd mod    && ./gradlew shadowJar serverJar   # build/libs/ytparty-0.1.0-bundle.jar (~33 MB) + -server.jar (~21 KB)
cd plugin && ./gradlew build                 # build/libs/ytparty-plugin-0.1.0.jar
cd relay  && CGO_ENABLED=0 go build -ldflags "-s -w" -o ytparty-relay .
```

The `…-bundle.jar` contains the mod classes, `fabric.mod.json` and ~600 LavaPlayer/YouTube classes
plus decoder natives. The ~33 MB come from deliberately bundling the natives for **all** platforms
(macOS, Linux x86-64/x86/arm/aarch64/musl, Windows x86/x86-64) so the same jar runs everywhere without
fetching anything. The relay builds with `CGO_ENABLED=0` into a static binary (`FROM scratch`-ready).

## Two mod jars (code stays together)

The source lives in **one** tree (`src/main` = server+common, `src/client` = audio/GUI). Two artifacts
come out of it:

| Jar | Task | Contents | For |
|---|---|---|---|
| `…-bundle.jar` (~33 MB) | `shadowJar` | client + server + all audio libs/natives | player client **and** Fabric server |
| `…-server.jar` (~21 KB) | `serverJar` | only `main` classes + `serverMeta/fabric.mod.json` (`environment: server`) | dedicated servers (no audio needed) |

Both load through the same `YtPartyMain` entrypoint; the server jar just leaves out the audio weight.

## Configuration

Only the relay is configurable (environment variables); Bukkit plugin and Fabric server mod have no
config — party defaults are hardcoded (private, public-join level `listen`) and changed per party
in-game. **No JSON anywhere.** Every option with its default and meaning is in
[`CONFIGURATION.md`](CONFIGURATION.md).

## MC 26.2 API changes (applied)

| Before (26.1) | 26.2 |
|--------|------|
| `Minecraft#getToastManager()` | `Minecraft.gui.toastManager()` |
| `Minecraft#setScreen(Screen)` | `Minecraft#setScreenAndShow(Screen)` (screen get/set moved into `Gui`) |

26.2 is a small update; the HUD render-state pipeline (`HudElement`/`GuiGraphicsExtractor`) and the rest compiled unchanged.

## MC 26.1 API changes (already applied)

| Before | 26.1 |
|--------|------|
| `mappings loom.officialMojangMappings()` | **dropped** (26.1 is unobfuscated) |
| `net.minecraft.resources.ResourceLocation` | `…resources.Identifier` |
| `player.displayClientMessage(c, false)` | `player.sendSystemMessage(c)` |
| custom `Screen.render(GuiGraphics …)` | GUI uses `StringWidget` |
| `new KeyMapping(…, "category.x")` | `new KeyMapping(…, KeyMapping.Category.MISC)` |
| `KeyBindingHelper#registerKeyBinding` | `KeyMappingHelper#registerKeyMapping` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()/clientboundPlay()` |
| `ServerPlayer.getServer()` | gone → get the server via `ServerLifecycleEvents.SERVER_STARTED` |
| `getGameProfile().getName()` | `getName().getString()` |
| `EditBox.setFormatter(BiFunction)` | `EditBox.addFormatter(TextFormatter)` |
| `mouseScrolled(double,double,double)` | `mouseScrolled(double x, double y, double dx, double dy)` |

## Runtime fixes (from real in-game tests)

- **Shadow relocation removed.** LavaPlayer's JNI symbol names derive from the fully qualified class
  name; a `relocate 'com.sedmelluq'` causes an `UnsatisfiedLinkError` in the Opus decoder. Classes stay
  in their original package.
- **Playback speed:** `COMMON_PCM_S16_LE` is 44100 Hz; the format is derived from the LavaPlayer constant
  instead of a hardcoded 48000 Hz (was ~8.8 % too fast).
- **Audio through OpenAL** on Minecraft's current device (`alcGetCurrentContext`) instead of `javax.sound`.
- **Seek stall fixed:** all OpenAL calls run only on the pump thread; control goes through `volatile`
  flags (previously a seek called `alSourceStop` from the render thread → inconsistent state).
- **Volume independent of MC master** (pure PCM scaling).
- **Dead tracks** (blocked/region-locked) auto-skip to the next instead of stalling.
- **Timeline:** seek sends `SET_POSITION` (absolute ms); shows the jump target for ~1.2 s before the live
  position catches up (no snapping back).
- **GUI live-consistent:** labels/markers/buttons are reconciled against real state each tick and rebuilt
  only on change; typed URL text survives.
- **Playlist scrolling:** the track list now scrolls with the mouse wheel (window clamped to the list,
  range indicator shown); previously only the first 8 tracks were reachable.

## Connection & robustness fixes

- **Relay sending never blocks the MC main thread:** a dedicated writer thread with a bounded queue
  writes to the relay (Java sockets have no write timeout — a stalled relay would otherwise freeze the
  client). Queue full → clean disconnect.
- **MC receiver gated while the relay is connected** (no cross-talk on modded servers); leaving an MC
  server does not reset the relay party.
- **STATE counts bounded** (≤ 500 tracks / ≤ 4096 members) before allocating → no OOM from a malicious relay.
- **TCP keepalive** (Go + Java), auth timeout (10 s) and write deadline (5 s) reap dead/stalled peers.
- **Relay: one async writer goroutine per connection** with a bounded queue. Broadcasts only enqueue
  under the global lock; socket writes happen off-lock, so one slow client no longer stalls everyone
  (its queue fills → only that connection drops). Replaces writes-under-the-global-lock.
- **Relay graceful shutdown** on `SIGINT`/`SIGTERM` (clean `docker stop`).
- **Relay resync on reconnect:** a new connection whose uuid is still a party member immediately
  receives the current STATE — a seamless resume after a brief network drop.
- **"Disconnect" during connect** aborts cleanly (generation counter).
- **Keybind localization:** `key.ytparty.open` ships an `en_us`/`de_de` name so it reads properly in
  Options → Controls (and is rebindable); in-game text is English to match the GUI.
- **Invite UX:** besides typing a name, on a server you can pick invitees from the live online-player list
  (a dedicated `InviteScreen` with a search box and wheel-scrolling). Built from standard `Button` widgets
  rather than a hand-drawn list, because 26.1 widgets render through an extracted render-state pipeline
  (`extractWidgetRenderState`) instead of direct `GuiGraphics` drawing.
- **Random party ids:** all backends mint unguessable token ids (no sequential `p1`, `p2`) so private
  parties can't be enumerated; access still requires invite/public.

## Performance & practices

- **Relay:** `PBKDF2` runs **once at startup** (not per connection); per connection only an X25519 keygen
  + one ML-KEM encapsulate + an HMAC — all microseconds. One goroutine per connection for reads, one for
  writes; a global mutex serializes only the (tiny) state mutation. The parser is bounds-checked with an
  error flag (no `panic`/`recover` as control flow), `gofmt`-clean, `go vet`-clean, uses `strconv`.
- **Client:** `PBKDF2` runs per connect on a background thread (never blocks the game); the GUI rebuilds
  only on real state change; OpenAL runs single-threaded on the pump thread.
- **String fidelity:** name/uri/title are passed through the relay as opaque blobs (no re-encoding →
  emoji preserved).
- **Version-current APIs:** ML-KEM-768 via the JDK 25 KEM API and Go 1.26 `crypto/mlkem` (both stdlib);
  `Math.clamp` (JDK 21+) on the client; `min`/`max` builtins and the `slices` package (Go 1.21+) in the
  relay. (PBKDF2 stays hand-rolled on both sides for byte-exact cross-language parity; the JDK 25 KDF API
  only covers HKDF, not PBKDF2.)

## Feature ideas

Not implemented — candidates for later:

| Idea | Note |
|---|---|
| Open the menu from the **main menu / title screen** | a title-screen button; lets you listen and manage outside a world (the keybind only fires in-world today) |
| **Persist** playlist + relay host/port across restarts | currently in-memory only; save to a client config (password optional) |
| **Loop / shuffle / repeat-one** playback modes | client-side; sync the mode in STATE |
| **Now-playing HUD** overlay | small on-screen current-track label |
| **Search picker** for `ytsearch:` | choose from results instead of auto-adding the top hit |
| **Playlist import** (paste many URLs / a YT playlist URL) | expand to tracks server-side or client-side |
| Show **who added** each track / queue semantics | needs a per-track requester field in the protocol |
| Optional **volume sync** | currently never synced; could be a toggle |
| Relay **rate-limiting / connection cap** | hardening for internet-exposed relays |
| **Crossfade / gapless** between tracks | audio-pipeline work in the pump thread |

## Open / deliberately not tested

- **Runtime in the actual game:** compiling + bundling is verified (also crypto interop and the relay
  party flow headless), but rendering + audio output + in-game sync are not — that needs a GUI/audio/MC
  environment. The audio path was verified with a standalone LavaPlayer harness (not shipped).
- **PartyScreen** shows up to 7 members without scrolling (parties are rarely larger; only the level
  controls of an 8th+ member would be out of reach in the UI — a benign edge case).
- **YouTube breaks regularly.** The built-in YT source in LavaPlayer is deprecated, hence
  `dev.lavalink.youtube`. On breakage: rebuild with an updated `common` version. `403` / "This video
  requires login" mostly hits server IPs; on a residential IP the WEB client usually works. If it
  persists: OAuth/PoToken for the youtube-source.
- **`plugin.yml api-version`** is `1.13` (the oldest value the field supports — it was introduced in 1.13). The plugin compiles against the Bukkit 1.8 API but runs on 1.13+ because the sync channel `ytparty:sync` is a namespaced channel; on older servers `api-version` is simply ignored.

## LavaPlayer alternatives (evaluated)

For "drop in a jar, it runs, no external binaries" LavaPlayer is the best choice (it decodes via
embedded native libs).

| Option | In-JVM, no binaries? | YT extraction | Verdict |
|--------|:--:|---------------|---------|
| LavaPlayer + youtube-source | yes | built in | **chosen**, de-facto standard |
| NewPipeExtractor | yes (extraction only) | very actively maintained | only stream URLs/metadata, wire up a decoder yourself |
| yt-dlp + ffmpeg | no | top | external binaries, against "plug and play" |
| Lavalink (own node) | no | built in | overkill for client-side |
| VLCJ / libvlc | no | – | system-wide install needed |

On acute YT breakage NewPipeExtractor is the most robust fallback (with your own decoder in front); as
long as you stay on LavaPlayer, a version bump of `dev.lavalink.youtube:common` is enough.
