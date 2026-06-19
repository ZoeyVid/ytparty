# Planned features

Features that are designed but **not yet implemented**. Listed with what they do, rough
implementation notes, and an effort estimate, so they can be picked up later.

## Effort overview

Effort is a rough T-shirt size. The biggest multiplier is whether a feature touches the **wire
protocol** — a protocol change has to be made in *four* places that must stay byte-compatible (client
mod, Paper plugin, Fabric server mod, Go relay), so "protocol = yes" roughly doubles the work of an
otherwise client-only change.

| Feature | Effort | Touches protocol? | Main cost |
|---|---|---|---|

| Now-Playing HUD | Low–Medium | no | new HUD callback + config + drawing |
| Loop / Shuffle / Repeat-One | Medium | yes (`mode` byte + `SET_MODE`) | 4 backends + advance logic + shuffle |
| Editable playlist text field | Medium | yes (`SET_PLAYLIST`) | 4 backends + title resolution / playlist expansion |
| Playlist-URL loading (add all tracks) | Low–Medium | yes (batch `ADD_MANY` / `SET_PLAYLIST`) | one batched frame instead of N ADDs + client expansion |
| "Who added" a track (player heads) | Low | no (protocol done) | player-head texture rendering in 26.1 pipeline only |
| Democratic skip-voting | Medium–High | yes (3 STATE fields + 2 ops) | most protocol surface + vote state/majority/clearing |
| DJ / suggestion queue | Medium | yes (`SUGGEST` + suggestion list in STATE) | suggestion store + approve/reject + GUI section |
| SponsorBlock segment skipping | Low–Medium | no (uses existing SEEK) | HTTP lookup + segment store + skip-at-position logic |
| "Who paused / skipped" (maybe) | Low | yes (actor field) | small, but only useful once the HUD exists |

## Now-Playing HUD

A small always-visible overlay (independent of the open GUI) showing the current track. Config
options: position (one of the four screen corners), on/off, and "always" vs "only briefly around a
track change" (~4 s after a change). No dedicated keybind. In a party it shows the synced track.
Implementation: a HUD render callback (Fabric `HudRenderCallback` / `HudElementRegistry`) that reads
the current track + paused state from `PlayerController` and draws a toast-like box.

**Effort: Low–Medium.** Client only. One HUD callback plus a few client config keys; the only mild
unknown is the exact 26.1 HUD-registration API.

## Editable playlist text field (import = export)

A single multi-line text field, pre-filled with all current track URLs (one per line), freely
editable. "Apply" replaces the whole playlist with the resolved lines. The same field is both the
export (read the current list) and the import (paste/edit lines). YouTube playlist links expand to
all their tracks. Needs a new protocol op **SET_PLAYLIST** (replace all tracks, MANAGE only) for the
party case; in solo it just replaces the local playlist. The client resolves titles / expands
playlist links before sending.

**Effort: Medium.** The op itself is simple, but it lands on all four backends, and the client side
needs batch resolution / playlist-link expansion (potentially many tracks) with sane progress and
caps. A multi-line `EditBox` (or scrolling text area) in the 26.1 widget-only GUI is straightforward.

## Playlist-URL loading (add all tracks)

Paste a YouTube **playlist** URL and have all of its tracks added at once (in solo, or to a party as a
manager). This existed once but was **removed**: in a relay party the client sent one `ADD` message
per track, and a playlist with more than ~128 tracks tripped the relay's per-connection flood limit
(`msgBurst = 128`) and got the connection dropped. Today a playlist URL is rejected and adds nothing —
only single-video URLs are accepted.

The flood-safe way to bring it back is to **not** send N separate `ADD`s. Add a batched op — either a
dedicated `ADD_MANY` (append a list of tracks in one frame) or reuse the planned `SET_PLAYLIST`
(replace the whole list in one frame). One frame for the whole playlist stays under the rate limit and
is also far less wasteful. The client still has to expand the playlist link to its tracks (LavaPlayer's
`playlistLoaded` already yields the full list) and cap the count (≤ 500, matching the per-party track
cap). Solo mode has no relay and no rate limit, but it should share the same code path and cap.

**Effort: Low–Medium.** The expansion already worked before removal; the real work is the batched
protocol op across all four backends (so the relay receives one frame, not 128+) plus a sensible cap
and optional progress feedback. Pairs naturally with the editable-playlist-text-field feature, which
needs the same `SET_PLAYLIST` op.

## Loop / Shuffle / Repeat-One

A playback-mode toggle cycling **Off → Repeat-All → Repeat-One → Shuffle**. Off = advance and stop at
the end; Repeat-All = wrap to the start; Repeat-One = replay the current track; Shuffle = pick a
random next track. Synced across a party (only MANAGE switches); works solo too (feature parity).
Needs a STATE `mode` byte and a C2S **SET_MODE**. On track end / skip, the advance logic consults the
mode.

**Effort: Medium.** One STATE byte + one op across four backends, plus the advance/auto-remove logic
has to branch on the mode in each backend and in the solo client. Shuffle needs a "don't repeat the
last track" pick to feel right.

## Democratic skip-voting

A party option (like "public") that a manager enables/disables dynamically. When enabled, any member
can cast a "skip" vote; once a strict majority of members have voted, the party skips to the next
track and the votes clear. Managers can still skip directly. Needs STATE fields
`skipVoteEnabled` + `voteCount` + `iVoted` and C2S **SET_SKIPVOTE** / **VOTE_SKIP**. Votes also clear
on track change or when the option is turned off.

**Effort: Medium–High.** The largest protocol surface of any planned feature: three new STATE fields
and two new ops on four backends, plus server-side vote bookkeeping (per-member vote set, majority
threshold, clearing on every track change / member leave / toggle-off) that must be enforced
identically everywhere.

## DJ / suggestion queue

Lets non-managers (LISTEN / INVITE level) **suggest** tracks without being able to change playback. A
member sends a suggested URL; it lands in a separate **suggestion list** (not the real queue) that
managers see in the GUI with approve / reject buttons. Approving moves it into the actual playlist
(reusing the normal ADD path, so the requester field still records who suggested it); rejecting drops
it. This builds directly on the existing permission system (LISTEN/INVITE/MANAGE) and gives listeners
a voice without handing them control.

Needs a C2S **SUGGEST** op (any member, carries uri + title) and a suggestion list in STATE (each entry:
uri, title, suggester), plus manager-only **APPROVE_SUGGESTION** / **REJECT_SUGGESTION** (by index or
id). The suggestion list is capped like the track list and cleared appropriately. Suggestions are
opaque blobs on the relay, exactly like tracks today.

**Effort: Medium.** One member-level op (the first C2S action a non-manager can take that the backend
acts on — the permission check inverts from "MANAGE only" to "any member"), a suggestion store on all
four backends, approve/reject plumbing, and a new GUI section showing pending suggestions. No audio
work. The trust model is unchanged: only managers ever mutate the real queue.

## SponsorBlock segment skipping

Integrate the open [SponsorBlock](https://sponsor.ajay.app) API to automatically skip sponsor / intro /
outro / self-promo segments inside a track. SponsorBlock returns, per YouTube video ID, a list of
`[start, end]` ranges (in seconds) with a category. Because playback is already position-based and the
party already has a SEEK mechanism, skipping a segment is just "when playback reaches `start`, seek to
`end`." A per-user config picks which categories to skip (sponsor on by default; intro/outro/etc.
optional), and the privacy-preserving hash-prefix query (send only the first 4 chars of the video-ID
hash, filter locally) avoids leaking exactly which video is playing.

In **solo** this is purely client-local: fetch segments when a track loads, skip locally. In a **party**
the segments must be applied consistently — the simplest correct design is that only the drift leader
(or any manager) performs the skip via the existing `SET_POSITION`, so everyone moves together and no
new protocol op is needed; segment lookup is still client-local. Edge cases: overlapping segments,
segments near the very end (let the track end normally), and segments shorter than the ~2 s SEEK
threshold (skip them client-locally without a network round-trip, or batch).

**Effort: Low–Medium.** No protocol change (reuses SEEK / SET_POSITION). The work is an HTTP client for
the SponsorBlock endpoint (with the hash-prefix privacy query and graceful failure when the API is
unreachable), a per-track segment store, the category config UI, and the skip-at-position logic in the
client tick. The YouTube video ID is already available from the track URI.

## "Who added" a track — player heads (protocol already done)

**Name and requester field are implemented** across all four backends and the wire protocol.
What remains is the visual: showing a small player-head texture next to each track.

Each track is stamped by the backend with the name + UUID of whoever added it (the backend knows the
sender). The playlist shows a small **player head** next to each track (rendered from the UUID, async
skin load, generic Steve/Alex head as fallback) with the requester's **name as a hover tooltip**.
Needs per-track `requesterName` + `requesterUuid` in STATE. The server/relay fills these on ADD.

**Effort: Medium.** The protocol part is small (two strings per track, filled on ADD). The real work
is client-side rendering: drawing a player head in the 26.1 extracted-render-state GUI pipeline and
loading the skin asynchronously with a fallback — fiddlier than a plain widget, plus a hover tooltip.

## Lower priority / maybe

- **"Who paused / skipped"** surfaced subtly (e.g. in the HUD or a tooltip, not chat — would be spammy
  otherwise). *(Currently dropped; listed only as a maybe.)* **Effort: Low**, but it needs an "actor"
  field on the relevant op/state and is only worth doing once the HUD exists to show it.

- **Aggregated drift reference (median / average of managers).** Today, with multiple managers, exactly
  one — the manager with the lexicographically smallest name — is elected drift leader and its position
  is the reference everyone syncs to (a single, stable, client-side choice with no protocol change). An
  alternative would be to sync everyone to the **median** (more robust than the average against a
  manager that stalls or buffers) of all managers' positions. It is conceptually fairer but costs more:
  (1) it can't be done client-side — every manager would have to **report** its position and a backend
  would aggregate and broadcast one SEEK, which is a real protocol change across all four backends; and
  (2) the reported positions are never sampled at the same instant (one manager reports at t, another
  300 ms later), so the aggregate is computed from slightly stale values and would need timestamp
  extrapolation that assumes cross-client clock sync we don't have. **Effort: Medium**, and it trades the
  current zero-protocol simplicity for marginal fairness — kept here as a documented option, not a plan.

## Video miniplayerFeature

A small in-game video player that renders a YouTube video as a texture — either as a HUD overlay
(corner of the screen) or as a configurable block/entity texture (projection mapping). Audio would
remain through LavaPlayer; the video track would be decoded independently and kept in sync with it.

**Approach:** a library like **vlcj** (Java bindings for LibVLC) decodes video frames into a
`ByteBuffer` each tick; the buffer is uploaded as an OpenGL texture (`GL_TEXTURE_2D`) and drawn by a
Fabric `HudRenderCallback` or via a custom block renderer. JavaFX `MediaPlayer` is a dependency-free
alternative but exports frames less conveniently and brings a large runtime.

**Sync:** LavaPlayer has no sub-second frame precision. The simplest sync strategy is to start both
decoders at the same wall-clock instant and let them drift (acceptable for music videos); a tighter
approach periodically compares audio position to video PTS and seeks the video decoder if they diverge
beyond a threshold.

**Effort: High.** No protocol change (video is client-local). The work is all in the client: adding a
native video decoder dependency (vlcj + LibVLC), frame upload to OpenGL per tick, HUD rendering in the
26.1 extracted-render-state pipeline, and audio/video sync. LibVLC must be present on the host system
or bundled (large binary). The feature is client-local and does not affect parties or the relay.
