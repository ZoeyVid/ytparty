package com.mcyt.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import javax.sound.sampled.FloatControl;
import java.util.ArrayList;
import java.util.List;

public class AudioEngine {
    private boolean isPaused = false;
    private int currentIndex = 0;
    private List<String> playlist = new ArrayList<>();
    private float volume = 50.0f; // 0.0 - 100.0

    private AudioPlayerManager playerManager;
    private AudioPlayer player;
    private LocalAudioOutput audioOutput;
    private String currentPlayingUrl = null;

    public AudioEngine() {
        playerManager = new DefaultAudioPlayerManager();
        AudioSourceManagers.registerRemoteSources(playerManager);
        
        player = playerManager.createPlayer();
        audioOutput = new LocalAudioOutput(player);
        audioOutput.start();
        
        setVolume(volume);
    }

    public void syncStateFromServer(JsonObject state) {
        this.isPaused = state.get("paused").getAsBoolean();
        this.currentIndex = state.get("currentIndex").getAsInt();
        
        JsonArray arr = state.getAsJsonArray("playlist");
        this.playlist.clear();
        for (int i = 0; i < arr.size(); i++) {
            this.playlist.add(arr.get(i).getAsString());
        }

        updateAudioPlayback();
    }

    private void updateAudioPlayback() {
        System.out.println("Audio state updated: Paused=" + isPaused + " TrackIndex=" + currentIndex + " PlaylistSize=" + playlist.size());
        
        player.setPaused(isPaused);

        if (!playlist.isEmpty() && currentIndex < playlist.size()) {
            String targetUrl = playlist.get(currentIndex);
            
            // Only load if it's a new track from the server
            if (!targetUrl.equals(currentPlayingUrl)) {
                currentPlayingUrl = targetUrl;
                playTrack(targetUrl);
            }
        } else {
            player.stopTrack();
            currentPlayingUrl = null;
        }
    }

    private void playTrack(String url) {
        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                player.playTrack(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty()) {
                    player.playTrack(playlist.getTracks().get(0));
                }
            }

            @Override
            public void noMatches() {
                System.out.println("No matches found for URL: " + url);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                System.out.println("Failed to load track: " + exception.getMessage());
            }
        });
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(100.0f, volume)); // Clamp 0-100
        
        if (audioOutput.getLine() != null && audioOutput.getLine().isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gainControl = (FloatControl) audioOutput.getLine().getControl(FloatControl.Type.MASTER_GAIN);
            // Linear to decibel conversion for smooth volume sliding
            float db = (float) (Math.log10(this.volume > 0.0f ? this.volume / 100.0f : 0.0001f) * 20.0f);
            gainControl.setValue(db);
        }
    }

    public float getVolume() { return volume; }
    public boolean isPaused() { return isPaused; }
    public List<String> getPlaylist() { return playlist; }
}
