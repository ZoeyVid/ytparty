# Configuration

## Relay

The only configurable component. All via environment variables:

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `YTPARTY_RELAY_PASSWORD` | **yes** | — | shared password; clients must match it. Printable ASCII only. The relay refuses to start without it and prints an example key. |
| `YTPARTY_RELAY_HOST` | no | `0.0.0.0` | listen address |
| `YTPARTY_RELAY_PORT` | no | `25599` | listen port |

Set the password in `compose.yaml`'s `environment:` section — the shipped file has an empty
`YTPARTY_RELAY_PASSWORD:` ready to fill in.

## Server mod / plugin

No configuration. New parties start private and joiners of a party made public get the lowest level;
managers change both per party in‑game.

## Client

Nothing to edit by hand. The in‑game screens save automatically: the relay connection (host, port,
password, remember‑password, autoconnect), volume, the Now‑Playing HUD, the picture‑in‑picture video
(on/off, size, position), SponsorBlock categories, and the repeat / auto‑remove toggles all persist
between sessions; the *Allowed sites* list persists too, but is only saved by its *Done* button
(*Cancel* or Esc discard your changes). Solo playlists are not persisted (party playlists live on the
backend). Key binds (open the party UI, toggle the video) are under Options → Controls.
The video needs `ffmpeg` installed; without it, the video switches itself off with a hint as
soon as it would start. Every URL that isn't a YouTube video or playlist or an ARD Mediathek video
(so also YouTube channel links and ARD Mediathek live or show pages) needs `yt-dlp` and `ffmpeg`: it is
resolved with `yt-dlp` and played with lavaplayer or `ffmpeg`, but only if it starts with one of the URL
prefixes under *Settings* → *Allowed sites* (one per line, empty by default; saved as `allowed-sites`
in `config/ytparty-client.properties`, the prefixes separated by `\n`). Scheme and host are compared
case‑insensitively, and a prefix only matches up to a `/`, `?`, `#` or the end of the URL:
`https://www.zdf.de` allows every page on www.zdf.de, but not `http://www.zdf.de/…`,
`https://www.zdf.de.example.com` or `https://www.zdf.de:8443`; `https://www.zdf.de/serien/` allows only
what's below it (a URL with a `.` or `..` path segment, also percent‑encoded, never matches); a bare
`https://` allows every https site. The list also applies to tracks other party members add: for any
other site your client doesn't contact it and reports the track as unplayable instead, and removing
the site of the current track from the list stops it.
