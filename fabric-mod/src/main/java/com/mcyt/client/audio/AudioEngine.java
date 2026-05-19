package com.mcyt.client.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import javax.sound.sampled.*;

public class AudioEngine {
    private final AudioPlayerManager playerManager;
    private final AudioPlayer player;
    private SourceDataLine sourceDataLine;
    private Thread playbackThread;
    private volatile boolean running = false;
    private float volume = 1.0f; // 0.0 to 1.0

    public AudioEngine() {
        this.playerManager = new DefaultAudioPlayerManager();
        AudioSourceManagers.registerRemoteSources(playerManager);
        // Ensure format is PCM standard
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS); // Wait, we need PCM
        // Actually, lavaplayer outputs PCM by default if we don't change it, or we can use COMMON_PCM_S16_BE.
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
        this.player = playerManager.createPlayer();
        initSound();
    }

    private void initSound() {
        AudioDataFormat format = playerManager.getConfiguration().getOutputFormat();
        AudioFormat javaFormat = new AudioFormat(
                format.sampleRate,
                16,
                format.channelCount,
                true,
                true // Big endian for COMMON_PCM_S16_BE
        );

        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, javaFormat);
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceDataLine.open(javaFormat);
            sourceDataLine.start();

            running = true;
            playbackThread = new Thread(this::playbackLoop, "MCYT-Audio-Playback");
            playbackThread.setDaemon(true);
            playbackThread.start();
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    private void playbackLoop() {
        while (running) {
            AudioFrame frame = player.provide();
            if (frame != null) {
                byte[] data = frame.getData();
                if (data != null && sourceDataLine != null) {
                    // Primitive volume control: modify PCM data if volume < 1.0
                    // Since it's big endian 16-bit:
                    if (volume != 1.0f) {
                        for (int i = 0; i < data.length; i += 2) {
                            short sample = (short) ((data[i] << 8) | (data[i + 1] & 0xFF));
                            sample = (short) (sample * volume);
                            data[i] = (byte) (sample >> 8);
                            data[i + 1] = (byte) (sample & 0xFF);
                        }
                    }
                    sourceDataLine.write(data, 0, data.length);
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void playTrack(String identifier) {
        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
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
                System.out.println("No matches for " + identifier);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                System.out.println("Load failed: " + exception.getMessage());
            }
        });
    }

    public void setPaused(boolean paused) {
        player.setPaused(paused);
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public void stop() {
        player.stopTrack();
    }
    
    public void destroy() {
        running = false;
        player.destroy();
        if (sourceDataLine != null) {
            sourceDataLine.stop();
            sourceDataLine.close();
        }
    }
}
