package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.audio.MusicPlayer;
import de.zoeyvid.ytparty.audio.SponsorBlock;
import de.zoeyvid.ytparty.net.ClientSync;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.playlist.Playlist;
import de.zoeyvid.ytparty.playlist.Track;
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
    private boolean repeatOne = false;
    private int partyGeneration;
    private int nextLocalId = 1;
    private List<SponsorBlock.Segment> segments = List.of();
    private String segmentsUri = "";

    private PlayerController() { audio.setOnEnd(() -> Minecraft.getInstance().execute(this::onTrackEnded)); audio.setOnError(() -> Minecraft.getInstance().execute(this::onTrackFailed)); }

    public void setSink(Sink s) { sink = s; }

    public Playlist playlist() { return playlist; }
    public int currentIndex() { return currentIndex; }
    public boolean paused() { return paused; }
    public long trackChangedAt() { return trackChangedAt; }
    public Track currentTrack() { return playlist.get(currentIndex); }
    public boolean autoRemovePlayed() { return autoRemovePlayed; }
    public int volume() { return volume; }
    public boolean inParty() { return inParty; }
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
    public void requestPublicList() { if (sink != null) sink.send(SyncProtocol.listPublic()); }
    public List<String> relayPlayers() { return relayPlayers; }
    public void onPlayerList(List<String> names) { relayPlayers = List.copyOf(names); }
    public void requestPlayerList() { if (sink != null) sink.send(SyncProtocol.listPlayers()); }

    public byte partySbFlags() { return partySbFlags; }
    public boolean repeatOne() { return repeatOne; }
    public void toggleRepeat() {
        boolean nv = !repeatOne;
        if (remote()) sink.send(SyncProtocol.setRepeat(nv));
        else { repeatOne = nv; ClientConfig.save(); }
    }
    public void setSponsorBlock(byte flags) {
        if (inParty) { if (canManage() && sink != null) sink.send(SyncProtocol.setSponsorBlock(flags)); }
        else ClientConfig.setSbFlags(flags);
    }

    public void tick() {
        sponsorBlockTick();
    }


    private void sponsorBlockTick() {
        if (paused || currentIndex < 0 || audio.duration() <= 0 || !segmentsUri.equals(loadedUri)) return;
        byte flags = inParty ? partySbFlags : ClientConfig.sbFlags();
        if ((flags & SponsorBlock.FLAG_ENABLED) == 0) return;
        long pos = audio.position();
        for (SponsorBlock.Segment s : segments) {
            if (SponsorBlock.categoryEnabled(flags, s.category()) && pos >= s.startMs() && pos < s.endMs() - 500) { audio.setPosition(s.endMs()); return; }
        }
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
    public void acceptInvite(String id) { if (sink != null) sink.send(SyncProtocol.join(id)); }
    public void dismissInvite(String id) { invites.removeIf(i -> i.id().equals(id)); }

    private boolean remote() { return sink != null && inParty && canManage(); }

    public void addUrl(String url, Runnable onDone) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) { if (onDone != null) onDone.run(); return; }
        audio.resolve(url, (uri, title) -> Minecraft.getInstance().execute(() -> {
            if (remote()) sink.send(SyncProtocol.add(uri, title));
            else if (!inParty && playlist.size() < 500) {
                playlist.add(new Track(nextLocalId++, uri, title, ""));
                if (currentIndex < 0) setIndexLocal(0);
                ClientConfig.save();
            }
            if (onDone != null) onDone.run();
        }), () -> { if (onDone != null) Minecraft.getInstance().execute(onDone); });
    }

    public void removeAt(int i) {
        if (remote()) { Track t = playlist.get(i); if (t != null) sink.send(SyncProtocol.remove(t.id())); return; }
        if (inParty) return;
        playlist.remove(i);
        if (i == currentIndex) setIndexLocal(Math.min(currentIndex, playlist.size() - 1));
        else if (i < currentIndex) currentIndex--;
        ClientConfig.save();
    }

    public void move(int from, int to) {
        if (remote()) { Track t = playlist.get(from); if (t != null) sink.send(SyncProtocol.move(t.id(), to)); return; }
        if (inParty) return;
        playlist.move(from, to);
        if (from == currentIndex) currentIndex = to;
        else if (from < currentIndex && to >= currentIndex) currentIndex--;
        else if (from > currentIndex && to <= currentIndex) currentIndex++;
        ClientConfig.save();
    }

    public void playIndex(int i) {
        if (remote()) { Track t = playlist.get(i); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); }
        else if (!inParty) setIndexLocal(i);
    }

    public void togglePause() {
        if (remote()) sink.send(SyncProtocol.setPaused(!paused));
        else if (!inParty) { paused = !paused; audio.setPaused(paused); }
    }

    public void setVolume(int v) { volume = Math.clamp(v, 0, 200); audio.setVolume(volume); }

    public void seekBy(long ms) {
        if (inParty) { if (canManage() && sink != null) sink.send(SyncProtocol.setPosition(audio.position() + ms)); }
        else audio.seekBy(ms);
    }

    public void applyRemoteSeek(long ms) { audio.setPosition(ms); }

    public void seekTo(long ms) {
        if (inParty) { if (canManage() && sink != null) sink.send(SyncProtocol.setPosition(ms)); }
        else audio.setPosition(ms);
    }

    public long position() { return audio.position(); }
    public long duration() { return audio.duration(); }

    public void createParty() {
        if (sink == null) return;
        List<Track> carry = playlist.view();
        sink.send(SyncProtocol.create());
        if (!carry.isEmpty()) sink.send(SyncProtocol.setPlaylist(carry));
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
        if (remote()) sink.send(SyncProtocol.setPlaylist(tracks));
        else if (!inParty) { playlist.replaceAll(tracks); setIndexLocal(tracks.isEmpty() ? -1 : 0); ClientConfig.save(); }
        if (onDone != null) onDone.run();
    }

    public void joinParty(String id) { if (sink != null) sink.send(SyncProtocol.join(id)); }
    public void leaveParty() { if (sink != null) sink.send(SyncProtocol.leave()); }
    public void invite(String name, byte level) { if (sink != null) sink.send(SyncProtocol.invite(name, level)); }
    public void setLevel(String name, byte level) { if (sink != null) sink.send(SyncProtocol.setLevel(name, level)); }
    public void kick(String name) { if (sink != null) sink.send(SyncProtocol.kick(name)); }
    public void setPublic(boolean pub, byte level) { if (sink != null) sink.send(SyncProtocol.setPublic(pub, level)); }

    public void toggleAutoRemove() {
        boolean nv = !autoRemovePlayed;
        if (remote()) { autoRemovePlayed = nv; sink.send(SyncProtocol.setAutoRemove(nv)); }
        else if (!inParty) { autoRemovePlayed = nv; ClientConfig.save(); }
    }

    public void skip() {
        if (inParty) { if (canManage() && sink != null) { Track t = playlist.get(currentIndex + 1); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); } return; }
        if (!inParty) setIndexLocal(currentIndex + 1);
    }

    public void previous() {
        if (inParty) { if (canManage() && sink != null) { Track t = playlist.get(currentIndex - 1); if (t != null) sink.send(SyncProtocol.setTrack(t.id())); } return; }
        if (!inParty) setIndexLocal(currentIndex - 1);
    }

    private void onTrackEnded() {
        if (repeatOne || (!autoRemovePlayed && playlist.size() == 1)) { audio.repeatCurrent(); return; }
        if (inParty) {
            if (canManage() && sink != null) {
                if (!autoRemovePlayed && currentIndex + 1 >= playlist.size() && playlist.size() > 0) sink.send(SyncProtocol.setTrack(playlist.get(0).id()));
                else sink.send(SyncProtocol.trackEnded(partyGeneration));
            }
            return;
        }
        if (autoRemovePlayed && currentIndex >= 0 && currentIndex < playlist.size()) {
            int at = currentIndex;
            playlist.remove(at);
            setIndexLocal(at >= playlist.size() ? -1 : at);
        } else setIndexLocal(currentIndex + 1 < playlist.size() ? currentIndex + 1 : 0);
    }

    private void onTrackFailed() {
        Track t = playlist.get(currentIndex);
        ClientSync.message("YT Party: couldn't play" + (t != null ? " \u201c" + t.title() + "\u201d" : " this track"));
        if (inParty) { if (canManage() && sink != null) { Track next = playlist.get(currentIndex + 1); if (next != null) sink.send(SyncProtocol.setTrack(next.id())); } }
        else setIndexLocal(currentIndex + 1);
    }

    public void applyState(SyncProtocol.State s) {
        inParty = true;
        partyId = s.partyId();
        myLevel = s.myLevel();
        isPublic = s.isPublic();
        publicJoinLevel = s.publicJoinLevel();
        members = s.members();
        invites.clear();
        paused = s.paused();
        autoRemovePlayed = s.autoRemovePlayed();
        partySbFlags = s.sponsorBlockFlags();
        repeatOne = s.repeatOne();
        partyGeneration = s.generation();
        playlist.replaceAll(s.tracks());
        currentIndex = s.currentIndex();
        Track t = playlist.get(currentIndex);
        if (t == null) { loadedUri = null; segments = List.of(); segmentsUri = ""; audio.stop(); return; }
        if (!t.uri().equals(loadedUri)) { loadedUri = t.uri(); trackChangedAt = System.currentTimeMillis(); loadSegments(t.uri()); audio.playIdentifier(t.uri(), title -> {}); }
        audio.setPaused(paused);
    }

    public void onPartyLeft() { inParty = false; myLevel = MANAGE; isPublic = false; autoRemovePlayed = true; repeatOne = false; partyGeneration = 0; members = new ArrayList<>(); partyId = ""; }

    public void onWorldDisconnect(boolean stopAudio) {
        onPartyLeft();
        if (stopAudio) { currentIndex = -1; loadedUri = null; audio.stop(); }
    }

    private void setIndexLocal(int i) {
        if (i < 0 || i >= playlist.size()) { currentIndex = -1; loadedUri = null; segments = List.of(); segmentsUri = ""; audio.stop(); return; }
        currentIndex = i;
        Track t = playlist.get(i);
        loadedUri = t.uri();
        trackChangedAt = System.currentTimeMillis();
        paused = false;
        loadSegments(t.uri());
        audio.playIdentifier(t.uri(), title -> {});
        audio.setPaused(false);
    }
}
