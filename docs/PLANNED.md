# Planned features

Designed but **not built**. The one thing that dominates effort is whether a feature touches the
**wire protocol**: a protocol change must be made in *four* byte‑compatible places (client mod, Bukkit
plugin, Fabric server mod, Go relay), so "protocol = yes" roughly doubles an otherwise client‑only
change. Several shipped pieces lower the cost below — `SET_PLAYLIST` (whole list in one frame),
id‑based track ops, manager‑client‑driven advance via `SET_TRACK`, the `repeatOne`/SponsorBlock
toggle‑sync pattern, and direct HUD drawing.

| Feature | Effort | Protocol? |
|---|---|---|
| Livestream playback | High (blocked upstream) | no |
| Playlist‑URL loading (add **all** tracks) | Low | no |
| Shuffle | Low | optional |
| "Who added" a track — player heads | Low–Medium | tiny |
| Action log (who paused / skipped / played) | Low–Medium | small |
| Skip‑voting ＋ open‑add mode | Medium–High | yes |

## Livestream playback

Livestreams are **rejected on purpose** — adding one reports that it isn't supported. The blocker is in
lavaplayer, not here: its HLS support (`HlsStreamTrack` → `MpegTsM3uStreamAudioTrack`) only reads
MPEG‑TS segments carrying ADTS AAC, while YouTube ships fMP4 segments for live. That path yields zero
audio frames and ends the track immediately, which previously looked like silence with a restart every
few seconds.

The DASH audio streams *do* decode, but a plain request returns a single ~2 s segment; continuous
playback needs the `sq` sequence parameter counted up and the init segment stitched in front of every
media segment. That means a custom source manager and a segment‑chaining stream — a project of its own,
which is why it isn't built.

## Playlist‑URL loading

Add **all** tracks of a YouTube playlist URL at once, as an explicit, opt‑in action. Right now a
playlist URL adds only its **first video** everywhere — on purpose, so pasting one link can't silently
balloon into hundreds of tracks. The source manager *does* resolve playlists, and `SET_PLAYLIST` already
sends a whole list in one frame, so this is client‑only: on a deliberate "add all" path, expand the
link, combine with the current tracks, cap at ≤500, send one `SET_PLAYLIST`. The point is that it stays
an intentional choice — never the silent expansion that used to happen.

**Effort: Low.** Resolution and the one‑frame send already exist; the work is the opt‑in UX plus lifting
the first‑track cap only on that path. Solo shares the same code (no relay, no rate limit).

## Shuffle

A toggle that advances to a **random** next track instead of the next in order. Like the shipped
Loop‑All (which is just auto‑remove off), it can be client‑driven: on track end the manager's client
sends `SET_TRACK(random id)` — no backend change. Open question is only visibility: manager‑local (no
protocol) vs a synced toggle (one STATE bool ＋ `SET_SHUFFLE`, mirroring `repeatOne`).

## "Who added" — player heads

The requester **name** already ships end‑to‑end ("by X" in the playlist). The upgrade is a small
player‑head next to each track. Needs the requester's **UUID** in STATE (one extra per‑track field);
then the client draws the head with async skin load and a Steve/Alex fallback, name as hover tooltip.
The rendering is the real work, not the protocol bit.

## Action log

Show **who** did each control action ("Zoey paused", "Alex skipped to …") — kept out of chat, shown as
a few recent lines in‑GUI and/or briefly on the HUD. The backend knows the actor but never forwards it
to the other clients, so add an actor to the broadcast: either a dedicated per‑action S2C frame (a true
history, one more op) or a cheaper last‑action pair on STATE (only the most recent). Display is easy now
that the HUD and list patterns exist.

## Skip‑voting ＋ open‑add mode

Two independent manager toggles that give non‑managers a voice; cheaper together (shared protocol
surface).

- **Skip‑voting:** any member can vote to skip; a strict majority skips and clears the votes (cleared
  also on track change / leave / toggle‑off). STATE `skipVoteEnabled` ＋ `voteCount` ＋ `iVoted`, C2S
  `SET_SKIPVOTE` / `VOTE_SKIP`; a passed vote uses the existing `SET_TRACK` advance.
- **Open‑add:** lets LISTEN/INVITE members add tracks directly, bounded by a manager‑set **per‑member
  rate limit**. STATE `openAddEnabled` ＋ `addRateLimit`, C2S `SET_OPEN_ADD`; the ADD permission check
  becomes `canManage || (openAddEnabled && underRateLimit)`. The per‑member bucket (same shape as the
  relay's IP bucket) is new backend state on all four backends — the main cost.
