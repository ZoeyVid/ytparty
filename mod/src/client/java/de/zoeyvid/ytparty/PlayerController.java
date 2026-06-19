package de.zoeyvid.ytparty;

import de.zoeyvid.ytparty.audio.MusicPlayer;
import de.zoeyvid.ytparty.audio.SponsorBlock;
import de.zoeyvid.ytparty.net.SyncProtocol;
import de.zoeyvid.ytparty.playlist.Playlist;
import de.zoeyvid.ytparty.playlist.Track;

import java.util.ArrayList;
import java.util.List;

public final class PlayerController {
    public static final PlayerController INSTANCE = new PlayerController();

    public interface Sink { void send(byte[] data); }

    private final Playlist playlist = new Playlist();
    private final MusicPlayer audio = new MusicPlayer();
    private Sink sink;
    private boolean inParty = false;
    private byte myLevel = 2;
    private boolean isPublic = false;
    private byte publicJoinLevel = 0;
    private List<SyncProtocol.Member> members = new ArrayList<>();
    private String partyId = "";
    private String pendingInviteId;
    private String pendingInviteFrom;
    private byte pendingInviteLevel;
    private int currentIndex = -1;
    private boolean paused = false;
    private boolean autoRemovePlayed = true;
    private int volume = 100;
    private String loadedUri;
    private List<SyncProtocol.PartyEntry> publicParties = List.of();
    private int publicListVersion;
    private long lastDriftSyncAt;
    private byte partySbFlags = SponsorBlock.FLAG_ALL;
    private boolean repeatOne = false;
    private List<SponsorBlock.Segment> segments = List.of();
    private String segmentsUri = "";

    private PlayerController() { audio.setOnEnd(this::next); }

    public void setSink(Sink s) { sink = s; }

    public Playlist playlist() { return playlist; }
    public int currentIndex() { return currentIndex; }
    public boolean paused() { return paused; }
    public boolean autoRemovePlayed() { return autoRemovePlayed; }
    public int volume() { return volume; }
    public boolean inParty() { return inParty; }
    public byte myLevel() { return myLevel; }
    public boolean canManage() { return myLevel >= 2; }
    public boolean canInvite() { return myLevel >= 1; }
    public boolean isPublic() { return isPublic; }
    public byte publicJoinLevel() { return publicJoinLevel; }
    public List<SyncProtocol.Member> members() { return members; }
    public List<Track> soloTracks() { return playlist.view(); }
    public void loadSolo(List<Track> tracks) { if (!inParty) playlist.replaceAll(tracks); }
    public String partyId() { return partyId; }
    public String pendingInviteId() { return pendingInviteId; }
    public String pendingInviteFrom() { return pendingInviteFrom; }
    public byte pendingInviteLevel() { return pendingInviteLevel; }
    public List<SyncProtocol.PartyEntry> publicParties() { return publicParties; }
    public int publicListVersion() { return publicListVersion; }
    public void onPublicList(List<SyncProtocol.PartyEntry> list) { publicParties = List.copyOf(list); publicListVersion++; }
    public void requestPublicList() { if (sink != null) sink.send(SyncProtocol.listPublic()); }

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
        long now = System.currentTimeMillis();
        if (inParty && canManage() && !paused && currentIndex >= 0 && sink != null && audio.duration() > 0 && now - lastDriftSyncAt >= 5000) {
            sink.send(SyncProtocol.reportPosition(audio.position()));
            lastDriftSyncAt = now;
        }
        sponsorBlockTick();
    }

    private void sponsorBlockTick() {
        if (paused || currentIndex < 0 || audio.duration() <= 0 || !segmentsUri.equals(loadedUri)) return;
        byte flags = inParty ? partySbFlags : ClientConfig.sbFlags();
        if ((flags & SponsorBlock.FLAG_ENABLED) == 0) return;
        if (inParty && !canManage()) return;
        long pos = audio.position();
        for (SponsorBlock.Segment s : segments) {
            if (SponsorBlock.categoryEnabled(flags, s.category()) && pos >= s.startMs() && pos < s.endMs() - 500) { seekTo(s.endMs()); return; }
        }
    }

    private void loadSegments(String uri) {
        segments = List.of();
        segmentsUri = uri == null ? "" : uri;
        if (uri == null) return;
        String vid = SponsorBlock.videoId(uri);
        if (vid == null) return;
        SponsorBlock.fetch(vid, segs -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (uri.equals(loadedUri)) { segments = segs; segmentsUri = uri; }
        }));
    }

    public void onInvited(String from, String id, byte level) { pendingInviteFrom = from; pendingInviteId = id; pendingInviteLevel = level; }
    public void acceptInvite() { if (pendingInviteId != null && sink != null) sink.send(SyncProtocol.join(pendingInviteId)); }

    private boolean remote() { return sink != null && inParty && canManage(); }

    public void addUrl(String url, Runnable onDone) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) { if (onDone != null) onDone.run(); return; }
        audio.resolve(url, (uri, title) -> {
            if (remote()) sink.send(SyncProtocol.add(uri, title));
            else if (!inParty && playlist.size() < 500) {
                playlist.add(new Track(uri, title, ""));
                if (currentIndex < 0) setIndexLocal(0);
                ClientConfig.save();
            }
            if (onDone != null) onDone.run();
        }, () -> { if (onDone != null) onDone.run(); });
    }

    public void removeAt(int i) {
        if (remote()) { sink.send(SyncProtocol.remove(i)); return; }
        if (inParty) return;
        playlist.remove(i);
        if (i == currentIndex) setIndexLocal(Math.min(currentIndex, playlist.size() - 1));
        else if (i < currentIndex) currentIndex--;
        ClientConfig.save();
    }

    public void move(int from, int to) {
        if (remote()) { sink.send(SyncProtocol.move(from, to)); return; }
        if (inParty) return;
        playlist.move(from, to);
        if (from == currentIndex) currentIndex = to;
        else if (from < currentIndex && to >= currentIndex) currentIndex--;
        else if (from > currentIndex && to <= currentIndex) currentIndex++;
        ClientConfig.save();
    }

    public void playIndex(int i) {
        if (remote()) sink.send(SyncProtocol.setIndex(i));
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

    public void applyRemoteSeek(long ms) { if (Math.abs(audio.position() - ms) > 3000) audio.setPosition(ms); }

    public void seekTo(long ms) {
        if (inParty) { if (canManage() && sink != null) sink.send(SyncProtocol.setPosition(ms)); }
        else audio.setPosition(ms);
    }

    public long position() { return audio.position(); }
    public long duration() { return audio.duration(); }

    public void createParty() { if (sink != null) sink.send(SyncProtocol.create()); }
    public void joinParty(String id) { if (sink != null) sink.send(SyncProtocol.join(id)); }
    public void leaveParty() { if (sink != null) sink.send(SyncProtocol.leave()); }
    public void invite(String name, byte level) { if (sink != null) sink.send(SyncProtocol.invite(name, level)); }
    public void setLevel(String name, byte level) { if (sink != null) sink.send(SyncProtocol.setLevel(name, level)); }
    public void setPublic(boolean pub, byte level) { if (sink != null) sink.send(SyncProtocol.setPublic(pub, level)); }

    public void toggleAutoRemove() {
        boolean nv = !autoRemovePlayed;
        if (remote()) sink.send(SyncProtocol.setAutoRemove(nv));
        else if (!inParty) autoRemovePlayed = nv;
    }

    public void next() {
        if (inParty) {
            if (canManage() && sink != null) sink.send(repeatOne ? SyncProtocol.setPosition(0) : SyncProtocol.setIndex(currentIndex + 1));
            return;
        }
        if (repeatOne) { setIndexLocal(currentIndex); return; }
        if (autoRemovePlayed && currentIndex >= 0 && currentIndex < playlist.size()) {
            int at = currentIndex;
            playlist.remove(at);
            setIndexLocal(at >= playlist.size() ? -1 : at);
        } else setIndexLocal(currentIndex + 1);
    }

    public void applyState(SyncProtocol.State s) {
        inParty = true;
        partyId = s.partyId();
        myLevel = s.myLevel();
        isPublic = s.isPublic();
        publicJoinLevel = s.publicJoinLevel();
        members = s.members();
        pendingInviteId = null;
        pendingInviteFrom = null;
        paused = s.paused();
        autoRemovePlayed = s.autoRemovePlayed();
        partySbFlags = s.sponsorBlockFlags();
        repeatOne = s.repeatOne();
        playlist.replaceAll(s.tracks());
        currentIndex = s.currentIndex();
        Track t = playlist.get(currentIndex);
        if (t == null) { loadedUri = null; segments = List.of(); segmentsUri = ""; audio.stop(); return; }
        if (!t.uri().equals(loadedUri)) { loadedUri = t.uri(); loadSegments(t.uri()); audio.playIdentifier(t.uri(), title -> {}); }
        audio.setPaused(paused);
    }

    public void onPartyLeft() { inParty = false; myLevel = 2; isPublic = false; autoRemovePlayed = true; repeatOne = false; members = new ArrayList<>(); partyId = ""; }

    public void onWorldDisconnect(boolean stopAudio) {
        onPartyLeft();
        if (stopAudio) { currentIndex = -1; loadedUri = null; audio.stop(); }
    }

    private void setIndexLocal(int i) {
        if (i < 0 || i >= playlist.size()) { currentIndex = -1; loadedUri = null; segments = List.of(); segmentsUri = ""; audio.stop(); return; }
        currentIndex = i;
        Track t = playlist.get(i);
        loadedUri = t.uri();
        paused = false;
        loadSegments(t.uri());
        audio.playIdentifier(t.uri(), title -> {});
        audio.setPaused(false);
    }
}
