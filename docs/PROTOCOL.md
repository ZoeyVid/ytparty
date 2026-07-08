# Protocol

All three backends (relay, Fabric server mod, Bukkit plugin) speak the same protocol and run the same
party logic (`Control.apply`), so a client behaves identically against any of them.

## Transport

One binary, length‑prefixed channel named **`ytparty:sync`**.

- **On a Minecraft server** (server mod / plugin) it rides Minecraft's plugin‑messaging — carried
  inside the player's game connection.
- **To a relay** it's a plain TCP connection carrying the same frames, AEAD‑encrypted after the
  handshake (see [`SECURITY.md`](SECURITY.md)).

Each frame is **one opcode byte** followed by its payload.

## Encoding

Fields use Java `DataOutputStream` conventions: `str` = 2‑byte length ＋ modified‑UTF‑8; `i32` /
`i64` = 4‑ / 8‑byte big‑endian; `u8` = one byte; `bool` = one byte (0/1). Opcode numbers are the
single source of truth in `common/Opcodes.java`.

## Permission levels

`LISTEN` (hear ＋ stay in sync) → `INVITE` (＋ invite others) → `MANAGE` (＋ full control). The level
column below is the minimum required.

## Client → server

| # | Name | Payload | Level | Effect |
|---|---|---|---|---|
| 0 | CREATE | — | any | create a new party; you become its manager |
| 1 | JOIN | `str id` | any\* | join party `id` (\*must be public or you were invited) |
| 2 | LEAVE | — *or* `str name` | self / MANAGE | leave; with a member name, kick that member |
| 3 | INVITE | `str name, u8 level` | INVITE | invite a player at `level` (capped to your own level) |
| 4 | SET_LEVEL | `str name, u8 level` | MANAGE | change a member's level |
| 5 | ADD | `str uri, str title` | MANAGE | append a track (backend assigns an id, records you as requester) |
| 6 | REMOVE | `i32 trackId` | MANAGE | remove that track |
| 7 | MOVE | `i32 trackId, i32 toIndex` | MANAGE | reorder a track |
| 8 | SET_TRACK | `i32 trackId` | MANAGE | jump to a track (also how skip / previous / select work) |
| 9 | SET_PAUSED | `bool` | MANAGE | play (false) / pause (true) |
| 10 | SET_POSITION | `i64 ms` | MANAGE | seek; bumps the generation and broadcasts `SEEK` |
| 11 | SET_PUBLIC | `bool, u8 level` | MANAGE | make public/private ＋ set the level public joiners get |
| 12 | SET_AUTOREMOVE | `bool` | MANAGE | drop a track once it finished (off = the list loops) |
| 13 | LIST_PUBLIC | — | any | request the public‑party list → `PUBLIC_LIST` |
| 14 | REANCHOR | `i32 gen, i64 pos` | MANAGE | position report to keep the anchor fresh; applied only if `gen` is current and `pos` is ahead. No broadcast |
| 15 | SET_SPONSORBLOCK | `u8 flags` | MANAGE | set the party's SponsorBlock categories (low 4 bits) |
| 16 | SET_REPEAT | `bool` | MANAGE | toggle repeat‑current‑track |
| 17 | TRACK_ENDED | `i32 gen` | MANAGE | the current track finished (ignored unless `gen` is current); backend advances per repeat / auto‑remove |
| 18 | SET_PLAYLIST | `i32 count`, then `str uri, str title` × count | MANAGE | replace the whole playlist in one frame |
| 19 | LIST_PLAYERS | — | any | request the online‑player list → `PLAYER_LIST` |

## Server → client

| # | Name | Payload | Meaning |
|---|---|---|---|
| 0 | STATE | full snapshot (below) | the party's current state — the primary sync message; each member gets it with their own level |
| 1 | INVITED | `str from, str partyId, u8 level` | you were invited to `partyId` by `from` at `level` |
| 2 | MESSAGE | `str text` | a human‑readable notice (rate‑limit, error, …) |
| 3 | LEFT | — | you left / were removed from the party |
| 4 | SEEK | `i64 ms, i32 gen` | jump to `ms`; carries the generation for the monotonic re‑anchor check |
| 5 | PUBLIC_LIST | `i32 count`, then `str id, i32 members, str currentTitle` × count | answer to `LIST_PUBLIC` |
| 6 | PLAYER_LIST | `i32 count`, then `str name` × count | answer to `LIST_PLAYERS` — online player names for the invite picker |

### STATE payload

In order: `str partyId`, `u8 yourLevel`, `bool isPublic`, `u8 publicJoinLevel`, `bool paused`,
`i32 currentIndex`, `bool autoRemove`, `u8 sponsorBlockFlags`, `bool repeat`, `i32 generation`,
`i64 elapsedMs`, `i32 trackCount` then per track `i32 id, str uri, str title, str requester`,
`i32 memberCount` then per member `str name, u8 level`.

## Synchronisation model

Position is stored as an **anchor**, not a running clock: a party keeps an epoch/offset
(`elapsed`) plus a **generation** counter.

- A manager's action mutates state; the backend re‑broadcasts `STATE`, and on a position change a
  `SEEK` carrying the current `generation`.
- A client re‑anchors to the party's position only when the incoming `generation` matches **and** the
  position advances monotonically — this is what stops the stutter/rewind a naïve last‑write‑wins
  would cause when several managers act at once.
- Repeat and natural track‑end bump the generation the same way, so a looped or advanced track
  re‑anchors cleanly. `REANCHOR` lets the driving client refresh the anchor without a visible jump.

Because the model lives in the shared `Control`, most new features are new *client‑side
interpretation* over the existing opcodes rather than new opcodes — and a genuinely new opcode has to
be added to all three backends at once.
