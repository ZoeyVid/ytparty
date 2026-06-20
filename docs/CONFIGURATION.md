# Configuration reference

Every backend is configured differently, but all share the same two party defaults
(`default-public`, `public-join-level`). The client mod needs no manual setup —
host/port/password for the relay are entered in the in-game **Relay** screen and then saved
automatically (see the client section below).

No configuration uses JSON. Formats per component: Paper = YAML (built into Bukkit), Fabric =
`.properties` (JDK built-in; YAML would need an extra library), relay = environment variables.

## Shared concepts

| Value | Meaning |
|---|---|
| `default-public` | Whether a newly created party is public right away. Public parties can be joined by anyone without an invite. |
| `public-join-level` | Permission level granted to players who join a **public** party: `listen`, `invite`, or `manage`. Invited players instead get the level chosen by the inviter. |

Permission levels: `listen` (hear/sync only), `invite` (+ invite others), `manage` (+ full control:
playlist, play/pause, seek, change levels, public settings).

## Paper plugin — `plugins/YtParty/config.yml`

| Key | Type | Default | Description |
|---|---|---|---|
| `default-public` | bool | `false` | New parties start public. |
| `public-join-level` | enum | `listen` | Level for joiners of a public party (`listen`/`invite`/`manage`). |

```yaml
default-public: false
public-join-level: listen
```

## Fabric server mod — `config/ytparty-server.properties`

Created automatically on first start. Same two keys as Paper.

| Key | Type | Default | Description |
|---|---|---|---|
| `default-public` | bool | `false` | New parties start public. |
| `public-join-level` | enum | `listen` | Level for joiners of a public party (`listen`/`invite`/`manage`). |

```properties
default-public=false
public-join-level=listen
```

## Relay — environment variables

| Variable | Default | Description |
|---|---|---|
| `YTPARTY_RELAY_PASSWORD` | *(none)* | Pre-shared key. **Must be printable ASCII.** **Required:** if unset the relay refuses to start, prints a randomly generated example key, and exits — set this variable (to the example or your own value) and start again. |
| `YTPARTY_RELAY_HOST` | `0.0.0.0` | Bind address. Use `127.0.0.1` to expose only locally. |
| `YTPARTY_RELAY_PORT` | `25599` | TCP listen port. |
| `YTPARTY_DEFAULT_PUBLIC` | `false` | New parties start public. |
| `YTPARTY_PUBLIC_JOIN_LEVEL` | `listen` | Level for joiners of a public party (`listen`/`invite`/`manage`). |

```sh
YTPARTY_RELAY_PASSWORD='choose-something-long' \
YTPARTY_RELAY_PORT=25599 \
./ytparty-relay
```

With Docker Compose the same variables are passed through `compose.yaml`
(`YTPARTY_RELAY_PASSWORD` is required there and read e.g. from a `.env` file).

## Client mod — `config/ytparty-client.properties`

Written and read automatically; you normally never touch it by hand. It remembers the relay
connection, your volume, and your solo playlist between sessions.

| Key | Type | Default | Description |
|---|---|---|---|
| `relay.host` | string | *(empty)* | Last relay host. |
| `relay.port` | int | `25599` | Last relay port. |
| `relay.remember-password` | bool | `true` | When on, the relay password is saved; turn it off in the Relay screen to omit it from the file. |
| `relay.password` | string | *(empty)* | Saved relay password — only present when `relay.remember-password` is `true`. |

> **Note:** The relay auth token lives in RAM only and is intentionally **not** persisted to disk.
> On restart the client presents no token and the relay issues a new one (TOFU). A token on disk would
> be an unencrypted credential readable by any process with file access, which is worse than just
> re-entering the password — so RAM-only is the right trade-off.
| `volume` | int | `100` | Playback volume (0–200), applied locally at the OpenAL source. |
| `sponsorblock.flags` | int | `15` | Your solo SponsorBlock preference as a bitmask: `0x01` master enable, `0x02` sponsor, `0x04` unpaid/self-promotion, `0x08` music-offtopic (`15` = all on). In a party the managers' setting applies instead; skipping always runs locally on each client. |
| `repeat` | bool | `false` | Your solo loop-current-track preference. In a party the managers' setting applies; looping always runs locally (the client replays a clone of the track). |
| `autoremove` | bool | `true` | Your solo auto-remove-played preference; **off = loop the whole playlist** (advance past the last track back to the first). In a party the managers' setting applies. |
| `hud.enabled` | bool | `true` | Now-Playing HUD on/off — a small overlay showing the current track + play state. Client-local, never synced. |
| `hud.corner` | int | `1` | HUD screen corner: `0` top-left, `1` top-right, `2` bottom-left, `3` bottom-right. |
| `hud.always` | bool | `false` | HUD visibility: `false` shows it only ~4 s around a track change, `true` keeps it always on screen. |
| `track.count` / `track.N.uri` / `track.N.title` | – | – | Your solo playlist (party playlists live on the backend, not here). |
