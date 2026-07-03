# External dependencies

## Relay (Go)

**None.** Go standard library only (`crypto/ecdh`, `crypto/mlkem`, `crypto/pbkdf2`, `crypto/aes`, `crypto/cipher`,
`crypto/hmac`, `crypto/sha256`, `crypto/rand`, `log/slog`, `net`, …). `go.mod` has no `require` entries → a plain
`CGO_ENABLED=0 go build` yields a fully static binary.

## Paper plugin

| Dependency | Version | Kind |
|---|---|---|
| `io.papermc.paper:paper-api` | `26.2.build.+` | **provided** (supplied by the server, not bundled) |

No bundled libraries. The YAML config uses Bukkit's built-in parser (no extra library).

## Fabric mod

### Provided (not bundled — supplied by game/loader/API)

| Dependency | Version |
|---|---|
| `com.mojang:minecraft` | `26.2` |
| `net.fabricmc:fabric-loader` | `0.19.3` |
| `net.fabricmc.fabric-api:fabric-api` | `0.154.0+26.2` (goes into `mods/`) |
| LWJGL / OpenAL | supplied by Minecraft |

### Bundled directly (only in the **full bundle jar**, not the server jar)

| Dependency | Version | For |
|---|---|---|
| `dev.arbjerg:lavaplayer` | `2.2.7` | audio extraction + decoding (in-JVM) |
| `dev.lavalink.youtube:common` | `1.18.1` | YouTube source for LavaPlayer |

### Pulled in transitively (via the two above, all bundled)

| Dependency | Version |
|---|---|
| `dev.arbjerg:lava-common` | `2.2.7` |
| `dev.arbjerg:lavaplayer-natives` | `2.2.7` (decoder natives, all platforms) |
| `org.apache.httpcomponents:httpclient` | `4.5.14` |
| `org.apache.httpcomponents:httpcore` | `4.4.16` |
| `com.fasterxml.jackson.core:jackson-databind` | `2.15.2` |
| `com.fasterxml.jackson.core:jackson-core` | `2.15.2` |
| `com.fasterxml.jackson.core:jackson-annotations` | `2.15.2` |
| `org.mozilla:rhino` | `1.7.15` (JS eval for the YT cipher) |
| `org.jsoup:jsoup` | `1.16.1` |
| `org.json:json` | `20240303` |
| `commons-codec:commons-codec` | `1.11` |
| `commons-io:commons-io` | `2.13.0` |
| `commons-logging:commons-logging` | `1.2` |
| `net.iharder:base64` | `2.3.9` |
| `org.jetbrains:annotations` | `24.0.0` |
| `org.slf4j:slf4j-api` | `2.0.7` |

The **server jar** (`-server.jar`) contains **none** of these — it only packs the project's own
server classes (dedicated servers never play audio).

## Build tooling

| Tool | Version | For |
|---|---|---|
| JDK (Temurin) | 25 | mod + plugin |
| Gradle (wrapper) | 9.6.1 | mod + plugin |
| Fabric Loom | 1.17.13 | mod |
| Gradle Shadow | 9.4.3 | mod (full bundle) |
| Go | 1.26 | relay |
