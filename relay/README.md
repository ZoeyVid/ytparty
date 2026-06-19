# YT Party relay

Standalone, encrypted TCP server that synchronizes parties for worlds **without** a Paper plugin or
Fabric server mod — even singleplayer. Written in Go, **standard library only**, builds to a fully
static binary. Clients connect from the mod's in-game **Relay** screen (host / port / password).

## Build

```
CGO_ENABLED=0 go build -ldflags "-s -w" -o ytparty-relay .
```

Static (`FROM scratch`-ready). Cross-compile e.g. for arm64:

```
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o ytparty-relay-arm64 .
```

## Run

```
YTPARTY_RELAY_PASSWORD='something-long' ./ytparty-relay
```

If `YTPARTY_RELAY_PASSWORD` is **not** set, the relay generates a random ASCII secret, prints it once,
and warns that it is lost on the next restart. The password must be **printable ASCII**.

Or with Docker (Dockerfile here, `compose.yaml` in the repo root):

```
docker build -t ytparty-relay .
docker run -e YTPARTY_RELAY_PASSWORD='something-long' -p 25599:25599 ytparty-relay
```

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `YTPARTY_RELAY_PASSWORD` | *(none → random, ephemeral)* | pre-shared key, printable ASCII |
| `YTPARTY_RELAY_HOST` | `0.0.0.0` | bind address |
| `YTPARTY_RELAY_PORT` | `25599` | listen port |
| `YTPARTY_DEFAULT_PUBLIC` | `false` | new parties start public |
| `YTPARTY_PUBLIC_JOIN_LEVEL` | `listen` | join level for public parties (`listen`/`invite`/`manage`) |

Full reference: [`../docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

## Encryption

Per connection: a hybrid **X25519 + ML-KEM-768** handshake (forward secrecy + post-quantum), session
key `SK = HMAC-SHA256(K, transcript ‖ ss_x ‖ ss_m)` where `K = PBKDF2-HMAC-SHA256(psk, …, 600000)`,
then **AES-256-GCM** per frame with direction-separated counters. Each connection has its own writer
goroutine, so a slow client cannot stall broadcasts to others. The server shuts down gracefully on
`SIGINT`/`SIGTERM`. Details and the threat model: [`../docs/SECURITY.md`](../docs/SECURITY.md).
