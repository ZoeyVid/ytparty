# Development

## Layout

- `mod/` — Fabric mod. Split source sets: `src/client` (UI, audio, relay client) and `src/main`
  (server‑side party). The shared party logic lives in `src/main/.../common/` (`Control`, `Opcodes`)
  and is compiled into both the server mod and the client's solo path.
- `plugin/` — Bukkit plugin.
- `relay/` — Go server + its `Dockerfile`.

## Building

Each component is independent. **Tool and library versions are pinned in the build files** — see them
rather than any number here:

| Component | Build | Versions in |
|---|---|---|
| mod | Gradle wrapper, JDK 25 → `./gradlew shadowJar serverJar` (bundle jar for clients ＋ slim server jar) | `mod/gradle.properties`, `mod/build.gradle` |
| plugin | Gradle wrapper, **JDK 8 toolchain** against the Bukkit 1.8 API → `./gradlew build` | `plugin/build.gradle` |
| relay | `go build`, or `docker build relay/` for the shipped multi‑arch image | `relay/go.mod`, `relay/Dockerfile` |

Note on the plugin: Gradle 9 itself needs JDK 17+ to *run*, so its CI installs JDK 8 (the compile
toolchain) **plus** a newer JDK to run Gradle. Compiling against the old Bukkit API guarantees the
plugin only uses API present since 1.8.

## CI

One workflow per component under `.github/workflows/` (`mod`, `plugin`, `relay`) plus lint workflows
(Dockerfile, shell, spelling, JSON). The relay workflow builds and pushes the multi‑arch Docker image
to DockerHub and GHCR; the mod and plugin workflows build and publish their jars.

## Notes

- Minecraft has been unobfuscated since **26.1** (1.21.11 was the last obfuscated version), so Loom
  must **not** set official Mojang mappings and there are no Yarn mappings to add. GUI is
  widget‑based (no `GuiGraphics`); see `CLAUDE.md`.
- The relay uses only the Go standard library — no third‑party Go modules.
