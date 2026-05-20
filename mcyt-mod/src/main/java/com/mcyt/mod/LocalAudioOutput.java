package com.mcyt.mod;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import javax.sound.sampled.*;

public class LocalAudioOutput extends Thread {
    private final AudioPlayer player;
    private SourceDataLine line;

    public LocalAudioOutput(AudioPlayer player) {
        this.player = player;
        this.setDaemon(true);
        this.setName("MCYT-Audio-Output");
        
        try {
            AudioDataFormat format = StandardAudioDataFormats.DISCORD_PCM_S16_BE;
            AudioFormat javaAudioFormat = new AudioFormat(
                    format.sampleRate,
                    16,
                    format.channelCount,
                    true,
                    true
            );
            
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, javaAudioFormat);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(javaAudioFormat);
            line.start();
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            if (line != null) {
                AudioFrame frame = player.provide();
                if (frame != null) {
                    byte[] data = frame.getData();
                    line.write(data, 0, data.length);
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        interrupt();
                    }
                }
            }
        }
        if (line != null) {
            line.drain();
            line.close();
        }
    }

    public SourceDataLine getLine() {
        return line;
    }
}
