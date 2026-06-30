package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.audio.MusicPlayer;
import de.zoeyvid.ytparty.audio.SponsorBlock;
import de.zoeyvid.ytparty.net.ClientSync;
import de.zoeyvid.ytparty.net.LocalSink;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.playlist.Playlist;
import de.zoeyvid.ytparty.playlist.Track;
import de.zoeyvid.ytparty.server.party.Party;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerController {
    public static final PlayerController INSTANCE = new PlayerController();

    public interface Sink { void send(byte[] data); }

    private static final byte INVITE = 1, MANAGE = 2;

    public record Invite(String id, String from, byte level) {}

    private final Playlist playlist = new Playlist();
    private final MusicPlayer audio = new MusicPlayer();
    private final LocalSink localSink = new LocalSink();
    private Sink backend;
    private Sink sink;
    private boolean inParty = false;
    private byte myLevel = MANAGE;
    private boolean isPublic = false;
    private byte publicJoinLevel = 0;
    private List<SyncProtocol.Member> members = new ArrayList<>();
    private String partyId = "";
    private final List<Invite> invites = new ArrayList<>();
    private int currentIndex = -1;
    private boolean paused = false;
    private boolean autoRemovePlayed = true;
    private int volume = 100;
    private String loadedUri;
    private long trackChangedAt;
    private List<SyncProtocol.PartyEntry> publicParties = List.of();
    private int publicListVersion;
    private List<String> relayPlayers = List.of();
    private byte partySbFlags = SponsorBlock.FLAG_ALL;
    private long pendingJoinElapsed = -1;
    private boolean repeatOne = false;
    private int partyGeneration;
    private int nextLocalId = 1;
    private List<SponsorBlock.Segment> segments = List.of();
    private String segmentsUri = "";

    private PlayerController() { sink = localSink; audio.setOnEnd(() -> Minecraft.getInstance().execute(this::onTrackEnded)); audio.setOnError(() -> Minecraft.getInstance().execute(this::onTrackFailed)); }

    public void setBackend(Sink s) { backend = s; sink = localSink; }

    public Playlist playlist() { return playlist; }
    public int currentIndex() { return currentIndex; }
    public boolean paused() { return paused; }
    public long trackChangedAt() { return trackChangedAt; }
    public Track currentTrack() { return playlist.get(currentIndex); }
    public boolean autoRemovePlayed() { return autoRemovePlayed; }
    public int volume() { return volume; }
    public byte myLevel() { return myLevel; }
    public boolean canManage() { return myLevel >= MANAGE; }
    public boolean canInvite() { return myLevel >= INVITE; }
    public boolean isPublic() { return isPublic; }
    public byte publicJoinLevel() { return publicJoinLevel; }
    public List<SyncProtocol.Member> members() { return members; }
    public String partyId() { return partyId; }
    public List<Invite> pendingInvites() { return List.copyOf(invites); }
    public List<SyncProtocol.PartyEntry> publicParties() { return publicParties; }
    public int publicListVersion() { return publicListVersion; }
    public void onPublicList(List<SyncProtocol.PartyEntry> list) { publicParties = List.copyOf(list); publicListVersion++; }
    public void requestPublicList() { if (backend != null) backend.send(SyncProtocol.listPublic()); }
    public List<String> relayPlayers() { return relayPlayers; }
    public void onPlayerList(List<String> names) { relayPlayers = List.copyOf(names); }
    public void requestPlayerList() { if (backend != null) backend.send(SyncProtocol.listPlayers()); }

    public byte partySbFlags() { return partySbFlags; }
    public boolean repeatOne() { return repeatOne; }
    public void toggleRepeat() { if (ctrl()) sink.send(SyncProtocol.setRepeat(!repeatOne)); }
    public void setSponsorBlock(byte flags) {
        if (hasParty()) { if (canManage() && backend != null) backend.send(SyncProtocol.setSponsorBlock(flags)); }
        else ClientConfig.setSbFlags(flags);
    }

    public void tick() {
        joinSeekTick();
        sponsorBlockTick();
    }

    private void sponsorBlockTick() {
        if (paused || currentIndex < 0 || audio.duration() <= 0 || !segmentsUri.equals(loadedUri)) return;
        byte flags = hasParty() ? partySbFlags : ClientConfig.sbFlags();
        if ((flags & SponsorBlock.FLAG_ENABLED) == 0) return;
        long pos = audio.position();
        for (SponsorBlock.Segment s : segments) {
            if (SponsorBlock.categoryEnabled(flags, s.category()) && pos >= s.startMs() && pos < s.endMs() - 500) { audio.setPosition(s.endMs()); if (ctrl()) sink.send(SyncProtocol.reanchor(partyGeneration, s.endMs())); return; }
        }
    }

    private void joinSeekTick() {
        if (pendingJoinElapsed < 0 || audio.duration() <= 0) return;
        audio.setPosition(pendingJoinElapsed);
        pendingJoinElapsed = -1;
    }

    private void loadSegments(String uri) {
        segments = List.of();
        segmentsUri = uri == null ? "" : uri;
        if (uri == null) return;
        String vid = SponsorBlock.videoId(uri);
        if (vid == null) return;
        SponsorBlock.fetch(vid, segs -> Minecraft.getInstance().execute(() -> {
            if (uri.equals(loadedUri)) { segments = segs; segmentsUri = uri; }
        }));
    }

    public void onInvited(String from, String id, byte level) { invites.removeIf(i -> i.id().equals(id)); invites.add(new Invite(id, from, level)); }
    public void acceptInvite(String id) { if (backend != null) backend.send(SyncProtocol.join(id)); }
    public void dismissInvite(String id) { invites.removeIf(i -> i.id().equals(id)); }

    private boolean ctrl() { return sink != null && canManage(); }
    public boolean hasParty() { return !partyId.isEmpty(); }

    public void addUrl(String url, Runnable onDone) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) { if (onDone != null) onDone.run(); return; }
        audio.resolve(url, (uri, title) -> Minecraft.getInstance().execute(() -> {
            if (ctrl()) sink.send(SyncProtocol.add(uri, title));
            if (onDone != null) onDone.run();
        }), () -> { if (onDone != null) Minecraft.getInstance().execute(onDone); });
    }

    public void removeAt(int i) {
        if (ctrl()) { Track t = playlist.get(i); if (t != null) sink.send(SyncProtocol.remove(t.id())); }
    }

    public void move(int from, int to) {
        if (ctrl()) { Track t = playlist.get(from); if (t != null) sink.send(SyncProtocol.move(t.id(), to)); }
    }

    public void playIndex(int i) { if (ctrl()) { Track t = playlist.get(i); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); } }

    public void togglePause() { if (ctrl()) sink.send(SyncProtocol.setPaused(!paused)); }

    public void setVolume(int v) { volume = Math.clamp(v, 0, 200); audio.setVolume(volume); }

    public void seekBy(long ms) { if (ctrl()) sink.send(SyncProtocol.setPosition(audio.position() + ms)); }

    public void applyRemoteSeek(long ms, int generation) { audio.setPosition(ms); partyGeneration = generation; if (pendingJoinElapsed >= 0) pendingJoinElapsed = ms; }

    public void seekTo(long ms) { if (ctrl()) sink.send(SyncProtocol.setPosition(ms)); }

    public long position() { return audio.position(); }
    public long duration() { return audio.duration(); }

    public void createParty() {
        if (backend == null) return;
        List<Track> carry = playlist.view();
        backend.send(SyncProtocol.create());
        if (!carry.isEmpty()) backend.send(SyncProtocol.setPlaylist(carry));
    }

    public void applyPlaylistText(String text, Runnable onDone) {
        List<String> urls = new ArrayList<>();
        for (String line : text.split("\n", -1)) { String u = line.trim(); if (!u.isEmpty()) urls.add(u); }
        if (urls.isEmpty()) { setPlaylistResolved(List.of(), onDone); return; }
        List<List<String[]>> slots = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) slots.add(List.of());
        AtomicInteger remaining = new AtomicInteger(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            int idx = i;
            audio.resolveAll(urls.get(i), res -> {
                slots.set(idx, res);
                if (remaining.decrementAndGet() == 0) Minecraft.getInstance().execute(() -> {
                    List<Track> out = new ArrayList<>();
                    for (List<String[]> slot : slots) for (String[] p : slot) if (out.size() < 500) out.add(new Track(nextLocalId++, p[0], p[1], ""));
                    setPlaylistResolved(out, onDone);
                });
            });
        }
    }

    private void setPlaylistResolved(List<Track> tracks, Runnable onDone) {
        if (ctrl()) sink.send(SyncProtocol.setPlaylist(tracks));
        if (onDone != null) onDone.run();
    }

    public void joinParty(String id) { if (backend != null) backend.send(SyncProtocol.join(id)); }
    public void leaveParty() { if (backend != null) backend.send(SyncProtocol.leave()); }
    public void invite(String name, byte level) { if (backend != null) backend.send(SyncProtocol.invite(name, level)); }
    public void setLevel(String name, byte level) { if (backend != null) backend.send(SyncProtocol.setLevel(name, level)); }
    public void kick(String name) { if (backend != null) backend.send(SyncProtocol.kick(name)); }
    public void setPublic(boolean pub, byte level) { if (backend != null) backend.send(SyncProtocol.setPublic(pub, level)); }

    public void toggleAutoRemove() { if (ctrl()) sink.send(SyncProtocol.setAutoRemove(!autoRemovePlayed)); }

    public void skip() { if (ctrl()) { Track t = playlist.get(currentIndex + 1); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); } }

    public void previous() { if (ctrl()) { Track t = playlist.get(currentIndex - 1); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); } }

    private void onTrackEnded() { if (ctrl()) sink.send(SyncProtocol.trackEnded(partyGeneration)); }

    private void onTrackFailed() {
        Track t = playlist.get(currentIndex);
        ClientSync.message("Couldn't play" + (t != null ? " \u201c" + t.title() + "\u201d" : " this track"));
        if (!hasParty() && ctrl()) { Track n = playlist.get(currentIndex + 1); if (n != null) sink.send(SyncProtocol.setTrack(n.id())); }
    }

    public void applyState(SyncProtocol.State s) {
        boolean wasInParty = inParty;
        inParty = true;
        partyId = s.partyId();
        sink = partyId.isEmpty() ? localSink : backend;
        myLevel = s.myLevel();
        isPublic = s.isPublic();
        publicJoinLevel = s.publicJoinLevel();
        members = s.members();
        invites.removeIf(i -> i.id().equals(s.partyId()));
        paused = s.paused();
        autoRemovePlayed = s.autoRemovePlayed();
        partySbFlags = s.sponsorBlockFlags();
        repeatOne = s.repeatOne();
        int prevGeneration = partyGeneration;
        partyGeneration = s.generation();
        playlist.replaceAll(s.tracks());
        currentIndex = s.currentIndex();
        Track t = playlist.get(currentIndex);
        if (t == null) { loadedUri = null; segments = List.of(); segmentsUri = ""; pendingJoinElapsed = -1; audio.stop(); return; }
        boolean trackChanged = false;
        if (!t.uri().equals(loadedUri)) { loadedUri = t.uri(); trackChangedAt = System.currentTimeMillis(); loadSegments(t.uri()); audio.playIdentifier(t.uri(), title -> {}); trackChanged = true; }
        else if (wasInParty && partyGeneration != prevGeneration) { trackChangedAt = System.currentTimeMillis(); audio.repeatCurrent(); trackChanged = true; }
        if (!wasInParty) pendingJoinElapsed = s.elapsed();
        else if (trackChanged) pendingJoinElapsed = -1;
        audio.setPaused(paused);
        if (partyId.isEmpty()) ClientConfig.save();
    }

    public void onPartyLeft() { inParty = false; myLevel = MANAGE; isPublic = false; autoRemovePlayed = true; repeatOne = false; partyGeneration = 0; members = new ArrayList<>(); partyId = ""; syncLocalParty(); sink = localSink; }

    public void onWorldDisconnect(boolean stopAudio) {
        onPartyLeft();
        if (stopAudio) { currentIndex = -1; loadedUri = null; audio.stop(); }
        syncLocalParty();
    }

    private void syncLocalParty() {
        Party p = localSink.party;
        p.tracks.clear();
        int max = 0;
        for (Track t : playlist.view()) { p.tracks.add(new Party.TrackRef(t.id(), t.uri(), t.title(), t.requester())); if (t.id() > max) max = t.id(); }
        p.nextTrackId = max + 1;
        p.currentIndex = currentIndex;
        p.paused = paused;
        p.autoRemovePlayed = autoRemovePlayed;
        p.repeatOne = repeatOne;
        p.sbFlags = ClientConfig.sbFlags();
        p.generation = partyGeneration;
        p.anchor(audio.position());
    }

}
