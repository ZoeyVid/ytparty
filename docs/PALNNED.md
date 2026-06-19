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
| Jump to current track | Trivial | no | scroll the list to one index |
| Drift correction in parties | Low–Medium | reuses SEEK + 1 interval report | interval reporting + threshold logic |
| Now-Playing HUD | Low–Medium | no | new HUD callback + config + drawing |
| Loop / Shuffle / Repeat-One | Medium | yes (`mode` byte + `SET_MODE`) | 4 backends + advance logic + shuffle |
| Editable playlist text field | Medium | yes (`SET_PLAYLIST`) | 4 backends + title resolution / playlist expansion |
| "Who added" a track | Medium | yes (per-track requester) | 4 backends + async player-head rendering (26.1 pipeline) |
| Democratic skip-voting | Medium–High | yes (3 STATE fields + 2 ops) | most protocol surface + vote state/majority/clearing |
| Gapless playback | High | no | OpenAL queue hand-off across track boundary, untestable without sound |
| "Who paused / skipped" (maybe) | Low | yes (actor field) | small, but only useful once the HUD exists |

## Gapless playback

No silence gap between tracks (track B starts the instant track A ends). Not crossfade — the tracks
do not overlap, they just butt together seamlessly. Implementation: pre-resolve and pre-buffer the
next track shortly before the current one ends, and feed its PCM into the same OpenAL source queue
without an `alSourceStop`/flush between tracks. The trickiest part is keeping the pump thread's queue
fed across the boundary so the source never underruns. Untestable without a sound device.

**Effort: High.** Client audio only (no protocol change), but the hardest single feature here: it
reworks the OpenAL pump to manage a multi-buffer queue and a look-ahead resolve, and the failure mode
(underrun = audible gap or stutter) can only be judged by ear on real hardware.

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

## Jump to current track

A button in the playlist screen that scrolls the (scrollable) track list to the currently playing
track (the ♪ row). Pure client UI, no protocol change.

**Effort: Trivial.** Set `scrollOffset` to the current index (clamped) and rebuild. A few lines in
`PlaylistScreen`.

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

## "Who added" a track

Each track is stamped by the backend with the name + UUID of whoever added it (the backend knows the
sender). The playlist shows a small **player head** next to each track (rendered from the UUID, async
skin load, generic Steve/Alex head as fallback) with the requester's **name as a hover tooltip**.
Needs per-track `requesterName` + `requesterUuid` in STATE. The server/relay fills these on ADD.

**Effort: Medium.** The protocol part is small (two strings per track, filled on ADD). The real work
is client-side rendering: drawing a player head in the 26.1 extracted-render-state GUI pipeline and
loading the skin asynchronously with a fallback — fiddlier than a plain widget, plus a hover tooltip.

## Drift correction in parties

Periodically re-sync playback position across a party so clients that have drifted apart snap back to
the manager's position. The managing client reports its position on an interval, the backend
broadcasts it, and followers correct if they are off by more than a small threshold. Builds on the
existing SEEK path.

**Effort: Low–Medium.** Mostly reuses the existing SEEK broadcast; adds a periodic position report
from the manager and a "only correct if off by > threshold" check on followers, so a small jitter
does not cause constant micro-seeks. No new STATE shape.

## Lower priority / maybe

- **"Who paused / skipped"** surfaced subtly (e.g. in the HUD or a tooltip, not chat — would be spammy
  otherwise). *(Currently dropped; listed only as a maybe.)* **Effort: Low**, but it needs an "actor"
  field on the relevant op/state and is only worth doing once the HUD exists to show it.
