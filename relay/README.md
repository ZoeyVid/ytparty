# YT Party relay

Standalone, end‑to‑end‑encrypted server that hosts party state so people on different Minecraft
servers (or none) can listen together. Pure Go, standard library only; shipped as a multi‑arch Docker
image (`zoeyvid/ytparty`, `ghcr.io/zoeyvid/ytparty`).

## Run

Use the shipped `compose.yaml` from the repo root: set `YTPARTY_RELAY_PASSWORD` in its
`environment:` section, then start it with:

```
docker compose up -d
```

It listens on **25599/tcp** by default (`image: zoeyvid/ytparty`, or `ghcr.io/zoeyvid/ytparty`). Clients enter the host, port and the same password in the
in‑game *Relay* screen.

## Configuration

`YTPARTY_RELAY_PASSWORD` (required, printable ASCII), `YTPARTY_RELAY_HOST` (default `0.0.0.0`),
`YTPARTY_RELAY_PORT` (default `25599`). See [`../docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

## Security

Password‑authenticated hybrid (X25519 ＋ ML‑KEM) forward‑secret handshake, AES‑256‑GCM traffic,
per‑IP rate limiting. See [`../docs/SECURITY.md`](../docs/SECURITY.md).
