# Security

## Model

The client mod plays audio (and, if turned on, a picture‑in‑picture video) and sends small control
frames; it opens no ports. It starts the system `ffmpeg` (video, livestreams, other sites) and, with
*Other sites* on, `yt-dlp` as child processes. To read YouTube's stream URLs, NewPipeExtractor runs
functions from YouTube's player JavaScript in the bundled Rhino interpreter, without access to Java
classes. The security surface is the **backend connection** and **who may control a party**.

- **On a Minecraft server** (server mod / plugin) the transport is Minecraft's own player connection —
  there is no extra crypto layer, and trust follows the server.
- **The relay** is meant to face the open internet, so it is end‑to‑end encrypted and gated by a shared
  password.

## Relay transport encryption

A pre‑shared password authenticates a hybrid, forward‑secret handshake:

- The password is stretched with **PBKDF2‑HMAC‑SHA256** (600k iterations) into a 32‑byte key.
- Both sides do an ephemeral **X25519** exchange **and** an **ML‑KEM‑768** (post‑quantum) exchange.
- The session key is `HMAC‑SHA256` of the password key over both handshake messages **and** both shared
  secrets — so it cannot be derived without the password, and it changes every connection.
- Traffic is **AES‑256‑GCM** with directional, counter‑based nonces.

This gives confidentiality and integrity, forward secrecy (ephemeral keys), replay resistance
(per‑direction counters bound to fresh ephemerals), and post‑quantum hardening (a future quantum
attacker who records traffic still faces ML‑KEM). The Java client and Go relay are byte‑compatible.
A wrong password simply fails the handshake. The password must be printable ASCII; the relay refuses to
start without one and prints an example key to copy.

## Party access

- Party IDs are **unguessable random tokens** (no sequential `p1`, `p2`), so private parties can't be
  enumerated — access needs an invite or the party being public.
- **Permission levels** (LISTEN / INVITE / MANAGE) gate what a member may do; only managers can change
  the playlist, playback, levels or public status.
- The relay **rate‑limits per IP** to blunt connection/again spam.

## Hardening

The published relay image is minimal and the shipped `compose.yaml` runs it locked down —
non‑root user, `cap_drop: ALL`, `no-new-privileges`, bridged networking. Keep the relay password out of
the compose file (use an `.env` / secret).

## Assumptions

The backend (relay operator, or the Minecraft server) sees party membership and playlist contents in
the clear — the encryption protects the link, not the operator. YouTube and ARD Mediathek URLs are
resolved and audio is fetched **by each client**, not by the backend. With *Other sites* turned on,
a party manager can make your client run `yt-dlp` and `ffmpeg` against any http(s) URL they add,
which shows your IP address to that site and can make your client request hosts in your local
network or on your own computer; that's why the setting is off by default.
