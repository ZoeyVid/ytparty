# Wire protocol & permissions

All three backends (Paper plugin, Fabric server mod, relay) speak the **same** byte protocol, so the
client mod drives them identically. Over the Minecraft channel it is `ytparty:sync` (raw bytes via
`DataOutputStream`); on the relay the same payload is wrapped in an encrypted frame (see
[`SECURITY.md`](SECURITY.md)).

## C2S (client → backend)

| Op | Name | Fields |
|----|------|--------|
| 0 | CREATE | – |
| 1 | JOIN | UTF partyId |
| 2 | LEAVE | – |
| 3 | INVITE | UTF playerName, byte level |
| 4 | SET_LEVEL | UTF playerName, byte level |
| 5 | ADD | UTF uri, UTF title |
| 6 | REMOVE | int index |
| 7 | MOVE | int from, int to |
| 8 | SET_INDEX | int index |
| 9 | SET_PAUSED | bool |
| 10 | SET_POSITION | long ms (absolute) |
| 11 | SET_PUBLIC | bool isPublic, byte joinLevel |
| 12 | SET_AUTOREMOVE | bool on |

## S2C (backend → client)

| Op | Name | Fields |
|----|------|--------|
| 0 | STATE | UTF partyId, byte myLevel, bool isPublic, byte publicJoinLevel, bool paused, int index, bool autoRemovePlayed, int trackCount, trackCount×(UTF uri, UTF title), int memberCount, memberCount×(UTF name, byte level, bool duplicate) |
| 1 | INVITED | UTF fromName, UTF partyId, byte level |
| 2 | MESSAGE | UTF text |
| 3 | LEFT | – |
| 4 | SEEK | long ms (absolute) |

On the relay the string fields (name, uri, title) are passed through as **opaque blobs** — full
fidelity including emoji. Level bytes: `0` = LISTEN, `1` = INVITE, `2` = MANAGE.

Volume is **never** synced (purely client-local). Auto-advance within a party is sent by only one
client with MANAGE; everyone else follows the resulting STATE.

**Auto-remove played** (`autoRemovePlayed`, default on, party-synced) drops a track from the playlist
once it finishes or is skipped to the immediate next one. A manual jump to any other index does not
remove anything. Backends apply the heuristic on `SET_INDEX`: a move to `currentIndex + 1` counts as an
advance and removes the current track; any other target is treated as a jump.

`duplicate` flags a member whose claimed UUID is shared by at least one other member in the same party.
It is only ever set by the relay (see below); the plugin and server mod always send `false`.

## Relay identity & token

On the relay only, after the encrypted handshake the client sends an identity frame
`blob(name) ‖ blob(uuid) ‖ blob(token)` (each blob = `u16` length prefix + bytes), and the relay replies
with `{1} ‖ blob(token)`. On the first connect the client sends an empty token and the relay issues a
fresh one (TOFU, RAM-only, expiring after inactivity); the client persists it and re-presents it on later
connects to re-authenticate without re-sending the password. The relay keys all of its state (connections,
parties, memberships) by **token**, not UUID, so two clients claiming the same UUID are kept apart — and
each is marked `duplicate` in STATE. If a new connection presents a token that already has a live
connection, it **replaces** that connection and keeps its party membership; a genuine disconnect (no
replacement) leaves the party.

## Permission system (three levels)

| Level | May |
|-------|-----|
| **LISTEN** | only listen / stay in sync |
| **INVITE** | listen + invite others |
| **MANAGE** | listen + invite + manage (playlist, play/pause, seek, change levels, public settings) |

Rules:

- A party lives **as long as at least one member has MANAGE**. When the last manager leaves (or demotes
  themselves) it is disbanded; everyone else receives `LEFT` and drops cleanly into solo mode. The
  creator starts with MANAGE.
- The inviter picks the level when inviting. **Someone with INVITE but not MANAGE cannot grant MANAGE** —
  the requested level is capped to the inviter's own.
- Managers can change member levels afterwards (`SET_LEVEL`).
- **Public parties:** managers toggle `public on/off` and set whether joiners get `listen` or `manage`.
  Anyone may join a public party without an invite.
- Defaults for new parties: Fabric `config/ytparty-server.properties`, Paper `config.yml`, relay env
  (`default-public`, `public-join-level`) — see [`CONFIGURATION.md`](CONFIGURATION.md).

Permissions are enforced **server-side** (identically on all three backends); the client UI only
shows/hides buttons accordingly.
