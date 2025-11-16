package com.yingwang.chinesechess.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Manages game audio including sound effects and background music
 */
class GameAudioManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var backgroundMusicPlayer: MediaPlayer? = null

    private var moveSoundId: Int = 0
    private var captureSoundId: Int = 0

    private var isSoundEnabled = true
    private var isMusicEnabled = true

    private var musicVolume = 0.3f // Lower volume for background music
    private var soundVolume = 0.7f // Higher volume for sound effects

    init {
        initializeSoundPool()
        initializeBackgroundMusic()
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()

        // Load sound effects
        moveSoundId = soundPool?.load(context, com.yingwang.chinesechess.R.raw.move_piece, 1) ?: 0
        captureSoundId = soundPool?.load(context, com.yingwang.chinesechess.R.raw.capture_piece, 1) ?: 0
    }

    private fun initializeBackgroundMusic() {
        try {
            backgroundMusicPlayer = MediaPlayer.create(context, com.yingwang.chinesechess.R.raw.background_music)
            backgroundMusicPlayer?.apply {
                isLooping = true
                setVolume(musicVolume, musicVolume)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playMoveSound() {
        if (isSoundEnabled) {
            soundPool?.play(moveSoundId, soundVolume, soundVolume, 1, 0, 1f)
        }
    }

    fun playCaptureSound() {
        if (isSoundEnabled) {
            soundPool?.play(captureSoundId, soundVolume, soundVolume, 1, 0, 1f)
        }
    }

    fun startBackgroundMusic() {
        if (isMusicEnabled && backgroundMusicPlayer?.isPlaying == false) {
            backgroundMusicPlayer?.start()
        }
    }

    fun pauseBackgroundMusic() {
        if (backgroundMusicPlayer?.isPlaying == true) {
            backgroundMusicPlayer?.pause()
        }
    }

    fun stopBackgroundMusic() {
        backgroundMusicPlayer?.apply {
            if (isPlaying) {
                stop()
                prepare()
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
    }

    fun setMusicEnabled(enabled: Boolean) {
        isMusicEnabled = enabled
        if (!enabled) {
            pauseBackgroundMusic()
        } else {
            startBackgroundMusic()
        }
    }

    fun setSoundVolume(volume: Float) {
        soundVolume = volume.coerceIn(0f, 1f)
    }

    fun setMusicVolume(volume: Float) {
        musicVolume = volume.coerceIn(0f, 1f)
        backgroundMusicPlayer?.setVolume(musicVolume, musicVolume)
    }

    fun release() {
        soundPool?.release()
        soundPool = null

        backgroundMusicPlayer?.release()
        backgroundMusicPlayer = null
    }
}
