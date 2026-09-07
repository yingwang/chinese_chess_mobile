package com.yingwang.chinesechess.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import com.yingwang.chinesechess.R

/**
 * The one audio object in the app: move and capture samples, tone fallbacks for check and
 * game over, and the looping guqin. MainActivity owns it and hands it to GameController,
 * so the controller no longer keeps a second SoundPool of the same two samples.
 */
class GameAudioManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var toneGenerator: ToneGenerator? = null
    private var backgroundMusicPlayer: MediaPlayer? = null

    private var moveSoundId = 0
    private var captureSoundId = 0

    private var isSoundEnabled = true
    private var isMusicEnabled = true

    private var musicVolume = 0.3f
    private var soundVolume = 0.7f

    init {
        initializeSoundPool()
        initializeBackgroundMusic()
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 50)
        } catch (_: Exception) {
            null
        }
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

        moveSoundId = soundPool?.load(context, R.raw.move_piece, 1) ?: 0
        captureSoundId = soundPool?.load(context, R.raw.capture_piece, 1) ?: 0
    }

    private fun initializeBackgroundMusic() {
        try {
            backgroundMusicPlayer = MediaPlayer.create(context, R.raw.background_music)
            backgroundMusicPlayer?.apply {
                isLooping = true
                setVolume(musicVolume, musicVolume)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playMoveSound() {
        if (!isSoundEnabled) return
        if (moveSoundId != 0) soundPool?.play(moveSoundId, soundVolume, soundVolume, 1, 0, 1f)
        else toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun playCaptureSound() {
        if (!isSoundEnabled) return
        if (captureSoundId != 0) soundPool?.play(captureSoundId, soundVolume, soundVolume, 1, 0, 1f)
        else toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
    }

    /** No sample shipped for check; a short alert tone stands in. */
    fun playCheckSound() {
        if (!isSoundEnabled) return
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
    }

    /** No sample shipped for game over either. */
    fun playGameOverSound() {
        if (!isSoundEnabled) return
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
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

    fun setMuted(muted: Boolean) {
        isSoundEnabled = !muted
        isMusicEnabled = !muted
        if (muted) pauseBackgroundMusic() else startBackgroundMusic()
    }

    fun isMuted(): Boolean = !isSoundEnabled

    fun release() {
        soundPool?.release()
        soundPool = null
        toneGenerator?.release()
        toneGenerator = null
        backgroundMusicPlayer?.release()
        backgroundMusicPlayer = null
    }
}
