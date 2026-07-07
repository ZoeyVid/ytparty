# Planned features

Features that are designed but **not yet implemented**. Listed with what they do, rough
implementation notes, and an effort estimate, so they can be picked up later.

## Already-built blocks to reuse

Several recent protocol additions are general-purpose and lower the cost of the features below:

- **`SET_PLAYLIST` (op 18)** — replaces a party's whole playlist in one frame (backend assigns ids +
  requester). Powers the **editable playlist text field** (shipped) and the playlist-URL feature; both are
  client-only.
- **`MusicPlayer.resolveAll(identifier)`** (shipped) — resolves a URL to *all* its `(uri, title)` pairs (a
  playlist link expands to every track). Built for the text field; the playlist-URL "add all" feature can
  reuse it directly.
- **Stable track ids** — every track has a backend id; `REMOVE`/`MOVE`/`SET_TRACK` are id-based. Useful for
  anything that references specific tracks (e.g. vote targets).
- **Client-driven advance via `SET_TRACK`** — the manager's client decides what happens when a track ends, so
  "which track is next" features can send the absolute `SET_TRACK(id)` (existing op) instead of `TRACK_ENDED`
  and need **no** backend change. Loop-All already works this way (auto-remove off = loop); Shuffle can too.
- **`TRACK_ENDED` + generation** — natural end is a generation-deduped signal; the generation is bumped on any
  real track change, which also drops stale position reports from the track that just ended.
- **Toggle-sync pattern** — `repeatOne` / `sponsorBlockFlags` show the established shape for a manager-set,
  party-synced, client-applied setting (a STATE field + a `SET_*` op), which Shuffle / skip-vote toggles
  can copy.
- **Direct HUD drawing** (shipped — Now-Playing HUD) — a `HudElement` registered via
  `HudElementRegistry.addLast` draws straight to `GuiGraphicsExtractor` (`fill` / `text` / `blit`); the
  first direct-draw code in the project and the precedent for the player-head rendering below.

## Effort overview

Effort is a rough T-shirt size. The biggest multiplier is whether a feature touches the **wire
protocol** — a protocol change has to be made in *four* places that must stay byte-compatible (client
mod, Bukkit plugin, Fabric server mod, Go relay), so "protocol = yes" roughly doubles the work of an
otherwise client-only change.

| Feature | Effort | Touches protocol? | Main cost |
|---|---|---|---|
| Playlist-URL loading (add **all** tracks) | **Low** | **no — reuses `SET_PLAYLIST` + `resolveAll`** | lift "first track only" in the Add field; the text field already expands playlist links |
| Shuffle | Low | maybe — synced toggle = 1 STATE bool + `SET_SHUFFLE`; manager-local = none | manager picks a random next track and sends the existing `SET_TRACK` |
| "Who added" a track (player heads) | Low–Medium | small (add requester UUID per track) | player-head texture rendering + async skin load |
| Who paused / skipped / played (action log) | Low–Medium | small (add an actor field / log op) | actor plumbing on the control ops + a small in-GUI log; display is easy now the HUD exists |
| Democratic skip-voting + open-add mode | Medium–High | yes (STATE fields + ops) | vote bookkeeping + per-member rate-limit enforcement on all backends |

## Playlist-URL loading (add all tracks)

Paste a YouTube **playlist** URL into the single-line **Add** field and have all of its tracks added at
once. Today the Add field still adds only the **first** track of a playlist URL; the **Edit list** text
field, by contrast, already expands playlist links to all their tracks (it uses `resolveAll`).

**Groundwork done:** the original blocker is gone. It used to send one `ADD` per track and a large
playlist tripped the relay's per-connection flood limit. Now `SET_PLAYLIST` sends the whole list in a
single frame, so there is **no new protocol op** and no flood risk. The append-all case is: client reads
the current tracks (it has them from STATE), expands the playlist link via LavaPlayer's `playlistLoaded`
(which already yields the full list), combines current + new, caps at ≤500, and sends one `SET_PLAYLIST`.

**Effort: Low.** Client-only: lift the "first track only" restriction in `MusicPlayer.resolve`, expand the
list, combine, cap, and optionally show progress. Solo shares the same path (no relay, no rate limit).

## Shuffle

A toggle that, instead of advancing to the next track in order, picks a **random** next track. (Loop-All is
already shipped — it is simply the `autoRemovePlayed = off` behaviour: the list loops at the end. See
PROTOCOL.md "Looping".)

**Likely also client-driven, like Loop-All.** Advancement on track end is already manager-driven, so Shuffle
can reuse the same trick: when a track ends, the manager's client sends `SET_TRACK(random track id)` instead
of `TRACK_ENDED`. No backend change and no new advance logic — `SET_TRACK` already does the rest. A "don't
immediately repeat the last track" pick keeps it feeling random.

The only open question is the **toggle's visibility**: keeping shuffle on/off purely in the manager's client
needs *no* protocol at all, but it isn't shown to other members and resets if the managing client changes. A
synced toggle (one STATE bool + a `SET_SHUFFLE` op, mirroring `repeatOne`) makes it visible and persistent at
the cost of a small protocol addition. Either is viable.

**Effort: Low.** Random-pick + send `SET_TRACK` is a few lines client-side. Add the small synced toggle only
if shuffle should be visible to everyone rather than a manager-local preference.

## "Who added" a track — player heads

The requester **name** is already implemented end-to-end (each track carries `requester`, filled by the
backend from the sender, shown as "by X" in the playlist). What remains is the visual upgrade: a small
**player-head** texture next to each track instead of plain text.

**Still needs a small protocol bit:** rendering a head needs the requester's **UUID** to load the skin,
and STATE currently carries only the requester name. So add a per-track `requesterUuid` (one more blob on
the relay; the MC backends already know the player UUID). Then the client draws the head (async skin load,
generic Steve/Alex fallback) with the name as a hover tooltip.

**Effort: Low–Medium.** The protocol part is tiny (one extra string per track on ADD). The real work is
client-side rendering: drawing a player head in the 26.1.2 extracted-render-state GUI pipeline and loading
the skin asynchronously with a fallback — fiddlier than a plain widget, plus the hover tooltip.

## Who paused / skipped / played — action log

Surface **who** performed each control action in a party — "Zoey paused", "Alex skipped to *Bohemian
Rhapsody*", "Zoey resumed" — so a synced room isn't a black box about who is driving it. Keep it **out of
chat** (that gets spammy fast); show it as a few recent lines in an in-GUI log and/or briefly on the
now-shipped Now-Playing HUD.

**Needs a small protocol bit.** The control ops (`SET_PAUSED`, `SET_TRACK`, skip, seek) already reach the
backend with a known sender, but that actor never reaches the *other* clients. So add an **actor** to the
broadcast — either a dedicated per-action S2C frame (`actor` name + action kind + optional track id/title),
which is the natural fit for a real *log*, or, minimally, a "last-action-by / last-action-kind" pair on
`STATE`. The per-action frame is cleaner (a true history, and no events lost when two actions collapse into
one STATE) at the cost of one more op the four components must agree on; the STATE pair is cheaper but only
ever shows the most recent action.

**Effort: Low–Medium.** The display is trivial now that both the HUD and an in-GUI list pattern exist; the
real cost is the protocol addition (kept byte-compatible across client mod, Bukkit plugin, Fabric server mod,
Go relay) and deciding which actions are worth logging — pause/resume/skip/seek/track-change, but probably
not volume or per-client SponsorBlock, which are local-only.

## Democratic skip-voting + open-add mode

Two optional party modes that give non-managers a voice; a manager can enable either or both independently.

**Skip-voting.** A manager toggle (like "public"). When enabled, any member can cast a "skip" vote;
once a strict majority of members have voted, the party skips to the next track and the votes clear.
Managers can still skip directly. Needs STATE fields `skipVoteEnabled` + `voteCount` + `iVoted` and
C2S **SET_SKIPVOTE** / **VOTE_SKIP**. Votes clear on track change (the generation already marks those)
or when the option is turned off. A passed vote triggers the existing id-based `SET_TRACK` advance.

**Open-add mode.** A separate manager toggle that lets non-managers (LISTEN / INVITE level) add tracks
directly to the playlist — without any approval step. To prevent flooding, the manager also sets a
**per-member rate limit** (e.g. max N adds per minute), stored in STATE and enforced by the backend.
Members who hit their limit get a `MESSAGE` telling them how long to wait. This is simpler than a DJ
suggestion queue (no approve/reject, no suggestion list in STATE) but the per-member rate-limit bookkeeping
is new backend work on all four backends.

Needs one more STATE field (`openAddEnabled` + `addRateLimit` int), and **SET_OPEN_ADD** (manager, carries
both values). The ADD op itself already exists; the only change is that the backend's permission check
becomes `canManage || (openAddEnabled && underRateLimit)` instead of `canManage` only. Rate-limit state
is per-member (a timestamp-ring or token bucket, same shape as the relay's existing IP bucket), cleared
on member leave or toggle-off.

**Effort: Medium–High.** The two features share protocol surface (both need manager toggles and STATE
fields), so they are cheaper together than separately. The skip-vote bookkeeping (per-member vote set,
majority threshold, clearing on every track change / member leave / toggle-off, enforced identically on
four backends) is the larger of the two. The open-add rate limit adds per-member mutable state to all
four backends but is otherwise straightforward.
