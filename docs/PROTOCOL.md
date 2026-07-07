# Wire protocol & permissions

All three backends (Bukkit plugin, Fabric server mod, relay) speak the **same** byte protocol, so the
client mod drives them identically. Over the Minecraft channel it is `ytparty:sync` (raw bytes via
`DataOutputStream`); on the relay the same payload is wrapped in an encrypted frame (see
[`SECURITY.md`](SECURITY.md)).

## C2S (client → backend)

| Op | Name | Fields |
|----|------|--------|
| 0 | CREATE | – |
| 1 | JOIN | UTF partyId |
| 2 | LEAVE | *optional* UTF playerName — empty/absent: leave yourself; a name (MANAGE only) kicks that member |
| 3 | INVITE | UTF playerName, byte level |
| 4 | SET_LEVEL | UTF playerName, byte level |
| 5 | ADD | UTF uri, UTF title — backend assigns the track id and derives the requester from the sender |
| 6 | REMOVE | int trackId |
| 7 | MOVE | int trackId, int toIndex |
| 8 | SET_TRACK | int trackId |
| 9 | SET_PAUSED | bool |
| 10 | SET_POSITION | long ms (absolute, manual scrub) |
| 11 | SET_PUBLIC | bool isPublic, byte joinLevel |
| 12 | SET_AUTOREMOVE | bool on |
| 13 | LIST_PUBLIC | *(no payload)* — request the public parties known to this backend (the relay's are global; a plugin/server-mod's are that server's) |
| 14 | REANCHOR | int generation, long pos — manager-only, **not** broadcast; tells the backend the playhead was just placed at `pos` (a local SponsorBlock skip), so its `elapsed` estimate stays exact. Applied only if `generation` matches (rejects skips racing a track change **or** a seek) **and** `pos` is ahead of the current estimate (so a slower manager reporting the same skip can't drag the estimate back to its lagging edge). No SEEK, nobody is re-synced. |
| 15 | SET_SPONSORBLOCK | byte flags |
| 16 | SET_REPEAT | bool on |
| 17 | TRACK_ENDED | int generation |
| 18 | SET_PLAYLIST | int count, count×(UTF uri, UTF title) — replaces the **entire** playlist; backend assigns fresh ids and fills requester from the sender |
| 19 | LIST_PLAYERS | *(no payload, relay-only)* — request the usernames currently connected to the relay; the requester must be in a party with at least INVITE rights |

## S2C (backend → client)

| Op | Name | Fields |
|----|------|--------|
| 0 | STATE | UTF partyId, byte myLevel, bool isPublic, byte publicJoinLevel, bool paused, int currentIndex, bool autoRemovePlayed, byte sponsorBlockFlags, bool repeatOne, int generation, long elapsed, int trackCount, trackCount×(int id, UTF uri, UTF title, UTF requester), int memberCount, memberCount×(UTF name, byte level) — members ordered by level descending, then name (case-insensitive), so the rendered list is stable across updates |
| 1 | INVITED | UTF fromName, UTF partyId, byte level |
| 2 | MESSAGE | UTF text |
| 3 | LEFT | – |
| 4 | SEEK | long ms (absolute), int generation — a `SET_POSITION` also bumps `generation` so it carries the new value; the client adopts it, keeping later `REANCHOR`/`TRACK_ENDED` gating in sync |
| 5 | PUBLIC_LIST | int count, count×(UTF id, int members, UTF currentTitle) — response to `LIST_PUBLIC`; sorted by member count descending |
| 6 | PLAYER_LIST | int count, count×(UTF username) — relay-only, response to `LIST_PLAYERS`; one entry per relay connection, so the same name may appear more than once if a player is connected multiple times |

On the relay the string fields (name, uri, title, requester) are passed through as **opaque blobs** — full
fidelity including emoji. Level bytes: `0` = LISTEN, `1` = INVITE, `2` = MANAGE.

Volume, looping and SponsorBlock skipping are applied **locally** on every client (see below); the rest of
playback is driven by the backend, which is the single source of truth that new joiners sync from.

## Stable track IDs

Each track gets a backend-assigned **id** (a per-party counter, starting at 1) on `ADD`. `REMOVE`, `MOVE`
and `SET_TRACK` all reference that id rather than a list index, so concurrent edits by multiple managers —
or an edit racing against an auto-remove that shifts indices — can never hit the wrong track. `SET_TRACK`
with an unknown id (the track was removed meanwhile) is ignored. The client maps id ↔ row for display;
`currentIndex` in STATE stays a plain position that is always consistent with the track list in the same
snapshot.

## Generation counter

STATE carries a monotonic **generation** that the backend bumps whenever the *identity of the currently
playing track changes* (a `SET_TRACK` to a different track, a `TRACK_ENDED` advance, or removal of the
current track — but **not** a `MOVE` of the current track, which keeps the same id). A manual `SET_POSITION`
also bumps it: a seek starts a new position epoch, which lets `REANCHOR` reject SponsorBlock skips that were
in flight from before the seek. It **de-duplicates
`TRACK_ENDED`**: with several managers, each one's client reports its own track end at a slightly different
moment. The op carries the generation the sender last saw; the backend acts only if it matches the current
one, then bumps it, so the staggered duplicates from the other managers are dropped.

## Advance vs. manual skip — `TRACK_ENDED` vs `SET_TRACK`

A track finishing naturally and a manager pressing *skip* both move to another track, but differ in whether
the finished track is dropped, so they are **separate signals**:

- **`TRACK_ENDED`** (natural end) — the backend advances to the next track, **wrapping to the first at the end
  of the list**, and if `autoRemovePlayed` is on, deletes the track that just played. The backend decides both
  the advance and the deletion purely from its own state; the client sends only the generation.
- **`SET_TRACK`** (manual skip / previous / clicking a row) — the backend only changes which track is current
  and **never** deletes or wraps. The X button next to a track is the explicit "delete + skip".

The trigger ("my track ended") is detected locally, but the actual mutation must go through the backend so
that the shared playlist and current index stay identical for everyone and correct for late joiners.

**Looping (auto-remove off = loop-all).** When `autoRemovePlayed` is **off**, nothing is deleted, so at the
end of the list `TRACK_ENDED` wraps the index back to the first track (`(index + 1) % count`) and the whole
list repeats. This is **backend-driven**: the client always sends `TRACK_ENDED`, never a special wrap. Because
`TRACK_ENDED` carries the generation, a late wrap from one manager can't override another manager's skip (the
stale generation is discarded) — which an absolute `SET_TRACK(first)` could not guarantee. Solo wraps locally.

**Repeat-One and a single-track loop** (auto-remove off, one track) also go through `TRACK_ENDED`, but the
index can't change — `(0 + 1) % 1 = 0` is the same track, and Repeat-One means "stay on this track". So the
backend leaves the index untouched, bumps the generation on its own, and broadcasts STATE. Clients see *same
current track, new generation* and restart it from the start (`repeatCurrent`). This keeps a re-sync point on
every loop and lets the generation dedupe repeated `TRACK_ENDED` from several managers, exactly like the
advance and wrap cases — the price is one round-trip gap per loop (seamless on LAN, audible over a relay).
Solo runs the same restart locally with no message. When `autoRemovePlayed` is **on**, played tracks drain out
of the list and playback stops once it empties (including a one-song list, which empties after one play).

## Looping & SponsorBlock

`SET_REPEAT` (op 16) and `SET_SPONSORBLOCK` (op 15) **sync the setting** across the party. SponsorBlock's
skipping then runs on every client independently; the Repeat-One restart is coordinated through `TRACK_ENDED`
(see above):

- **Repeat** (`repeatOne`) — when a track ends with repeat on, the manager sends `TRACK_ENDED`; the backend
  bumps the generation without moving the index and broadcasts STATE, so every client restarts the track
  together. Solo replays it locally.
- **SponsorBlock** — each client fetches the segments for the current track and seeks past enabled ones
  locally (`sponsorBlockFlags`, see [`CONFIGURATION.md`](CONFIGURATION.md) for the bits).

## Position sync — boundaries only

There is **no continuous drift correction**. Clients re-align at discrete moments rather than being nudged
toward a running reference:

- **Track boundaries** — every client (re)starts the current track from `0` on the STATE change, so a
  `SET_TRACK`, a `TRACK_ENDED` advance or a loop wrap re-synchronises the whole party.
- **Pause / resume** — `paused` lives in STATE, so a pause freezes everyone at their position and resume
  continues from there.
- **Manual seek** — `SET_POSITION(ms)` makes the backend broadcast a single absolute `SEEK(ms)` to **all**
  members; each member applies it directly. Since a `SEEK` is now only ever a deliberate manual jump, there
  is no tolerance window — even a small jump propagates exactly.

- **Join mid-track** — STATE carries `elapsed`, the backend's estimate of the current playhead position. The
  backend re-anchors its internal start time on **every** playhead jump it can know about — track start
  (anchor 0), manual `SET_POSITION` (anchor `ms`), and a manager's local SponsorBlock skip (anchor `endMs`
  via `REANCHOR`, gated by `generation` so a skip racing a track change or seek is dropped, and applied only if it moves the estimate forward so the fastest manager's skip wins). Because no jump ever happens *between* two anchors, `elapsed` is simply the playhead, so a
  **freshly joining** client seeks straight to it — no SponsorBlock reconstruction, no segment list needed.
  Everyone already in the party ignores `elapsed`. The only residual is a ~one-buffer / ~one-round-trip lead
  (the backend counts from when it broadcast/received, slightly ahead of audible playback); it does not
  accumulate and resolves at the next boundary.

The trade-off is that within a single track the only thing keeping clients together is how closely they
started it: `playIdentifier` resolves and buffers at slightly different speeds per client, so a load-latency
offset (typically well under a second) persists until the next boundary. An earlier server-side median that
SEEK-corrected this continuously was removed — it pulled managers back toward round-trip-delayed reference
positions and fought their own local jumps (e.g. a SponsorBlock skip), making the position oscillate.

**One identity per party.** A given UUID or username can be a member of a party only once. The relay
rejects a `JOIN` whose UUID or (case-insensitive) username already belongs to a current member of that
party, replying with a `MESSAGE`. The *same* identity may still hold several independent connections to the
relay at once (e.g. two clients, or two different parties) — it just cannot sit in one party twice. The MC
backends use authenticated UUIDs where one player already means one connection, so the rule is automatically
satisfied there and only the relay enforces it.

## Solo → party import

`SET_PLAYLIST` (op 18) replaces a party's whole playlist in a single frame. The client uses it for the
**solo → party** carry-over: on `createParty` it sends `CREATE` followed by one `SET_PLAYLIST` carrying the
solo tracks, instead of one `ADD` per track. This is a single message regardless of playlist size, so it no
longer risks tripping the per-connection message rate limit the way a burst of `ADD`s could. Because bulk
import is now one message, that limit was tightened (burst 16, refill 4/s) — comfortable for interactive
use while still bounding abuse.

## Relay identity & token

On the relay only, after the encrypted handshake the client sends an identity frame
`blob(name) ‖ blob(uuid) ‖ blob(token)` (each blob = `u16` length prefix + bytes), and the relay replies
with `{1} ‖ blob(token)`. On the first connect the client sends an empty token and the relay issues a
fresh one (TOFU, RAM-only, expiring after inactivity); the client persists it and re-presents it on later
connects to re-authenticate without re-sending the password. The relay keys all of its state (connections,
parties, memberships) by **token**, not UUID, so distinct connections stay distinct even when they claim the
same UUID; a second connection with an identity already present in a party is refused entry to *that party*
(see "One identity per party" above) but may exist and join elsewhere. If a new connection presents a token
that already has a live connection, it **replaces** that connection and keeps its party membership; a genuine
disconnect (no replacement) leaves the party.

## Permission system (three levels)

| Level | May |
|-------|-----|
| **LISTEN** | only listen / stay in sync |
| **INVITE** | listen + invite others |
| **MANAGE** | listen + invite + manage (playlist, play/pause, seek, change levels, kick members, public settings) |

Rules:

- A party lives **as long as at least one member has MANAGE**. When the last manager leaves (or demotes
  themselves) it is disbanded; everyone else receives `LEFT` and drops cleanly into solo mode. The
  creator starts with MANAGE.
- The inviter picks the level when inviting. **Someone with INVITE but not MANAGE cannot grant MANAGE** —
  the requested level is capped to the inviter's own.
- The invitee must be **online** when invited (on the server for the plugin/server-mod, connected to the
  relay for the relay) — an offline name is rejected and the inviter is told the player isn't online.
- Managers can change member levels afterwards (`SET_LEVEL`).
- Managers can **kick** a member by sending `LEAVE` with that member's name as payload (empty payload still
  means "leave yourself"). The kicked member receives `LEFT` and drops to solo. Reusing `LEAVE` avoids a
  separate op and shares the same disband/cleanup path; a non-manager's name payload is ignored.
- **Public parties:** managers toggle `public on/off` and set whether joiners get `listen` or `manage`.
  Anyone may join a public party without an invite.
- New parties always start private; joiners of a public party get `listen` (hardcoded on all three backends, changed per party in-game).

Permissions are enforced **server-side** (identically on all three backends); the client UI only
shows/hides buttons accordingly.
