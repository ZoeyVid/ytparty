# Security model

## Scope

Designed for a **group of friends**, not for open hosting. A shared password (PSK) is the trust
boundary. Within it participants are semi-trusted: permissions are enforced server-side, but anyone
holding the PSK may take part.

## Transport encryption per backend

| Backend | Transport | Encryption |
|---|---|---|
| Bukkit plugin | MC custom channel | rides Minecraft's game connection (AES in online mode) |
| Fabric server mod | MC custom channel | same |
| Relay | own TCP socket | **its own** layer (below) — Minecraft does *not* cover this socket |

The relay socket is opened by the mod itself and lives outside Minecraft, so it brings its own
crypto on **both** ends (Go relay + Java `RelayClient`).

## Relay crypto (hybrid, forward-secret, post-quantum)

- **PSK key:** `K = PBKDF2-HMAC-SHA256(psk, "ytparty-relay-v1", 600000, 32)`. The relay uses Go's
  standard-library `crypto/pbkdf2`; the mod hand-rolls the same construction on top of JDK's
  `HmacSHA256` (JDK's own `PBKDF2WithHmacSHA256` would re-encode the password and diverge). Both produce
  identical bytes for an ASCII PSK, which the live handshake verifies — a mismatch would fail the GCM tag.
- **Per-connection handshake (hybrid X25519 + ML-KEM-768):**
  - client → `cn(16) ‖ X25519-pub(32) ‖ ML-KEM-768 encapsulation key(1184)`
  - relay → `sn(16) ‖ X25519-pub(32) ‖ ML-KEM-768 ciphertext(1088)`
  - both derive `ss_x` (X25519 ECDH) and `ss_m` (ML-KEM), then
    `SK = HMAC-SHA256(K, "ytparty-sk-v2" ‖ msg1 ‖ msg2 ‖ ss_x ‖ ss_m)`.
- **Session:** every frame is `AES-256-GCM(SK, nonce, msg)` with a 12-byte nonce
  `dir(1) ‖ 000 ‖ counter(8 BE)`, separate counters per direction → no nonce reuse, replay-safe
  within a session.
- **Forward secrecy:** the X25519/ML-KEM keys are ephemeral and discarded after the handshake — a PSK
  leaked later decrypts **no** captured sessions.
- **Post-quantum:** ML-KEM-768 (FIPS 203) against "harvest now, decrypt later"; the hybrid holds as
  long as X25519 **or** ML-KEM is secure.
- **Authentication:** `K` feeds into `SK`, so only PSK holders derive the same key. The first encrypted
  frame (name+uuid) authenticates implicitly; a wrong PSK or tampering (including a MITM that swaps the
  ephemeral keys) makes the GCM tag fail → connection closed.
- **ASCII PSK:** must be printable ASCII. Java's and Go's stdlib encode non-ASCII passwords differently
  (Latin-1 vs UTF-8), which would make the KDF diverge. The relay otherwise refuses to start and the
  mod refuses to connect. If no PSK is set the relay **refuses to start** — it prints a randomly generated
  example key and exits, rather than ever running unauthenticated.

Cross-checked: PBKDF2 against RFC vectors, X25519/ML-KEM shared secrets byte-for-byte Java 25 ↔ Go
in both directions, and the full handshake live mod-crypto ↔ relay binary over TCP.

## Confidentiality of party content

The transport is encrypted client↔relay (and client↔server inside Minecraft's connection), but it is
**not end-to-end**: the relay (and a server plugin/mod) sees the party's playlist and state in plaintext
— it has to, in order to store it and enforce permissions. This is intentional; full E2E (a group key the
relay can't read) is a non-goal here.

What *is* guaranteed: **non-members cannot read another party's playlist.** A client only ever receives
STATE for a party it belongs to. Joining a non-public party requires being on its invite list — knowing
the party id is not enough. Party ids are random, unguessable tokens (not sequential), so parties can't be
enumerated or probed either. Public parties are joinable by anyone with the PSK by design.

- Permissions are enforced **server-side** (LISTEN/INVITE/MANAGE); the UI is display only.
- Joining is invite- or public-gated (no joining arbitrary parties).
- Malformed/truncated packets are caught and ignored without killing the connection.
- Incoming level bytes are clamped to `0..2`.
- `ADD` is capped: max 500 tracks, uri ≤ 1000, title ≤ 200 bytes → no memory flooding.
- Party ids are random unguessable tokens (all backends) → private parties can't be enumerated or probed.
- Disbanding notifies everyone else (`LEFT`) instead of leaving them stuck in a dead party.

## Client-side robustness

- **No decode path can be made to OOM:** STATE counts are bounded (≤ 500 tracks / ≤ 4096 members)
  before allocating, and the public-party and player list decodes never pre-size a collection from the
  wire count → a malicious relay cannot make the client allocate a huge buffer from a forged length.
- **Sending never blocks the MC main thread:** a dedicated writer thread with a bounded queue writes to
  the relay (Java sockets have no write timeout — a stalled relay would otherwise freeze the client).
  Queue full → clean disconnect.
- **The MC receiver is gated while the relay is connected** (no cross-talk on a modded server); leaving
  an MC server does not reset the relay party.
- "Disconnect" during connect aborts cleanly (generation counter).
- **TCP keepalive** (Go + Java), an auth timeout and a write deadline reap dead/stalled peers.
- The GUI password field is masked.

## Relay availability

The relay uses **one writer goroutine per connection** with a bounded queue. Broadcasts only enqueue
(non-blocking) under the global lock — the actual socket writes happen off-lock, per connection. So a
single slow or malicious client can no longer stall everyone else's broadcasts: if its queue fills, only
**that** connection is dropped. The relay also shuts down gracefully on `SIGINT`/`SIGTERM` (clean
`docker stop`).

## Residual risks / non-goals

These are deliberate properties of the friends-group model, not open structural bugs — after the
per-connection async writers there is no remaining "one client stalls everyone" failure mode, and the
crypto/permission paths are cross-checked.

- **Authentication & impersonation:** the **relay's only auth is the PSK** — it then trusts the name/uuid
  a client sends and never verifies it against Mojang. So anyone with the PSK can claim any identity, and
  this is true in **online *and* offline mode** (the relay isn't a Mojang-authenticated Minecraft server,
  just a socket gated by the password). Two mitigations narrow this: the relay keys all of its state by a
  per-client **token** (issued TOFU on first connect, re-presented afterwards), not by UUID, so one client
  cannot hijack another's session or membership by claiming its UUID; and a party admits any UUID or username
  **only once** — the relay refuses a join whose identity already belongs to a member, so an impersonator
  cannot sit alongside the real person in the same party (they can still connect, just not share that party).
  It remains a friends model — keep the PSK among people you trust. By contrast, on the **plugin/server-mod** backends
  the sync rides Minecraft's own connection, so on an online-mode server identities are verified by
  Minecraft itself (an offline-mode server verifies nothing, but that is the server's choice, not this mod's).
- **Tokens are RAM-only and expire** after inactivity; they are an authenticator, not a capability to a
  party — a genuine disconnect still leaves the party, and a token is bound to the UUID it was issued to, so
  re-presenting it only ever resumes that same identity: a token replayed under a different UUID is treated
  as a brand-new client, never a resume.
- **Secrets (party ids, resume tokens) are CSPRNG-generated** from `crypto/rand` over an ambiguity-free
  alphabet (no `0/O/1/I/l`) using rejection sampling — 8-character party ids, 24-character tokens. Rejection
  sampling discards the few byte values that would otherwise skew the result, avoiding the modulo bias a
  plain `byte % len(charset)` would introduce.
- **PSK brute-force is online-only** (no offline material): each attempt costs the attacker a full
  PBKDF2-600k plus a handshake. Still, use a long, random PSK.
- **Rate limiting & connection caps** are built in (fixed, sensible defaults — no configuration): a global
  cap on concurrent connections, a per-IP connection cap, a per-IP connection-attempt token bucket, and a
  per-connection message-rate token bucket. An IP that opens too many connections is rejected; a client that
  floods messages has the excess silently dropped while the connection stays up. For an internet-exposed
  deployment a firewall or reverse proxy in front is still sensible; the relay binds `0.0.0.0` by default.
  The **plugin and Fabric server-mod backends** apply the same per-player message-rate token bucket — flood
  messages from one player are silently dropped (the player stays connected) — as defense-in-depth on top of
  the server's own packet handling.
- No official TLS/Noise framework — a hand-assembled but cross-checked construction from stdlib
  primitives (stdlib KDF/KEM/AEAD on each side; the framing and handshake glue are ours).
  If you want something more "official": TLS 1.3 with the `X25519MLKEM768` group.
