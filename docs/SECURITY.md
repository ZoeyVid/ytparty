# Security

## Model

The client mod plays audio (and, if turned on, a picture‑in‑picture video) and sends small control
frames; it opens no ports. It starts the system `ffmpeg` (video, livestreams, other sites; all audio
with the slim jar) and, for allowed sites, `yt-dlp` as child processes. To read YouTube's stream
URLs, NewPipeExtractor runs functions from YouTube's player JavaScript in the bundled Rhino
interpreter, without access to Java classes. The security surface is the **backend connection** and
**who may control a party**.

- **On a Minecraft server** (server mod / plugin) the transport is Minecraft's own player connection —
  there is no extra crypto layer, and trust follows the server.
- **The relay** is meant to face the open internet, so it is end‑to‑end encrypted and gated by its
  public key, which clients keep like a password.

## Relay transport encryption

The relay has a static **X25519** key pair. Clients enter its public key (in the *Relay* screen's
password field); the private key never leaves the relay. The handshake follows the Noise NK pattern
with an added post‑quantum exchange:

- The client sends an ephemeral **X25519** key **and** an ephemeral **ML‑KEM‑768** encapsulation key;
  the relay answers with its own ephemeral X25519 key, the ML‑KEM ciphertext and two confirmations.
- An access key is `HMAC‑SHA256`, keyed with the relay's public key, over both handshake messages, the
  ephemeral X25519 secret and the ML‑KEM secret — so it cannot be derived without the public key.
- The session key is `HMAC‑SHA256`, keyed with the access key, over the X25519 secret between the
  client's ephemeral key and the relay's static key, which only the real relay can compute.
- The client checks both confirmations before it sends anything else: a failed first one means a wrong
  key, a failed second one means the other side knows the public key but isn't the relay.
- Traffic is **AES‑256‑GCM** with directional, counter‑based nonces. Every frame after the handshake is
  padded to the next power of two (at least 256 bytes), so its length tells little.

This gives confidentiality and integrity, forward secrecy (ephemeral keys), replay resistance
(per‑direction counters bound to fresh ephemerals), relay authentication that the clients' shared key
can't fake, and post‑quantum confidentiality (a future quantum attacker who records traffic still
faces ML‑KEM). The relay's authentication itself is classical X25519; it only counts at connection time
and would also need the public key. The Java client and Go relay are byte‑compatible. Whoever has the
public key can connect but can't pose as the relay, so keep it like a password. The relay refuses to
start without a valid private key, prints an example one to copy, and logs the public key at every
start.

## Party access

- Party IDs are **unguessable random tokens** (no sequential `p1`, `p2`), so private parties can't be
  enumerated — access needs an invite or the party being public.
- **Permission levels** (LISTEN / INVITE / MANAGE) gate what a member may do; only managers can change
  the playlist, playback, levels or public status.
- The relay **rate‑limits per IP** (IPv6 per /48) to blunt connection/again spam, caps every handshake
  message at its exact size and gives a handshake 5 s. Failed handshakes are logged with the IP
  (`auth failed`), e.g. for fail2ban.
- At login the relay checks the name (Vanilla's rule: 1–16 printable ASCII characters, no space), the
  UUID and the token, and rejects anything else; see [`PROTOCOL.md`](PROTOCOL.md) for all field limits.

## Hardening

The published relay image is minimal and the shipped `compose.yaml` runs it locked down —
non‑root user, `cap_drop: ALL`, `no-new-privileges`, bridged networking. Keep the relay's private key
out of the compose file (use an `.env` / secret). The client writes its config owner‑only where the file
system supports it, as it holds the relay's public key.

## Assumptions

The backend (relay operator, or the Minecraft server) sees party membership and playlist contents in
the clear — the encryption protects the link, not the operator. Names and UUIDs aren't checked with
Mojang, so whoever can connect can pose as another player, and a name isn't unique on a relay (an
invite reaches every connection with it). YouTube and ARD Mediathek URLs are
resolved and audio is fetched **by each client**, not by the backend. For the sites in your
*Allowed sites* list (empty by default), a party manager can make your client run `yt-dlp` and
`ffmpeg` against any URL on those sites they add, which shows your IP address to those sites. The
media `yt-dlp` finds there is parsed by lavaplayer and its native decoders (fdk‑aac, mpg123, Opus,
Vorbis) inside the game process, so a file that crashes a decoder takes the game down, not just a
child process; only what lavaplayer can't play goes to `ffmpeg`. What such a site returns can also
make your client request hosts in your local network or on your own computer, so only allow sites you
trust (a bare `https://` allows every https site). URLs of other sites are refused before `yt-dlp`
runs; a prefix only matches at a URL boundary, so `https://www.zdf.de` doesn't allow
`https://www.zdf.de.example.com` or `https://www.zdf.de@example.com`.
