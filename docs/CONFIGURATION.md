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
password, remember‑password, autoconnect), volume, the Now‑Playing HUD, SponsorBlock categories, and
the repeat / auto‑remove toggles all persist between sessions. Solo playlists are not persisted (party
playlists live on the backend).
