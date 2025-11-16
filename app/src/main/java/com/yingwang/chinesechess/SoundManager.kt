package com.yingwang.chinesechess

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.AudioManager

/**
 * Manages game sound effects
 * Plays sounds for moves, captures, check, and game over events
 */
class SoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var toneGenerator: ToneGenerator? = null
    private var enabled = true

    // Sound IDs - will be loaded from raw resources
    private var moveSoundId: Int = -1
    private var captureSoundId: Int = -1
    private var checkSoundId: Int = -1
    private var gameOverSoundId: Int = -1
    private var selectSoundId: Int = -1

    init {
        initializeSoundPool()
        initializeToneGenerator()
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

        // Try to load sound files if they exist in raw resources
        // If not found, we'll use ToneGenerator fallback
        try {
            val moveResId = context.resources.getIdentifier("move", "raw", context.packageName)
            if (moveResId != 0) {
                moveSoundId = soundPool?.load(context, moveResId, 1) ?: -1
            }

            val captureResId = context.resources.getIdentifier("capture", "raw", context.packageName)
            if (captureResId != 0) {
                captureSoundId = soundPool?.load(context, captureResId, 1) ?: -1
            }

            val checkResId = context.resources.getIdentifier("check", "raw", context.packageName)
            if (checkResId != 0) {
                checkSoundId = soundPool?.load(context, checkResId, 1) ?: -1
            }

            val gameOverResId = context.resources.getIdentifier("gameover", "raw", context.packageName)
            if (gameOverResId != 0) {
                gameOverSoundId = soundPool?.load(context, gameOverResId, 1) ?: -1
            }

            val selectResId = context.resources.getIdentifier("select", "raw", context.packageName)
            if (selectResId != 0) {
                selectSoundId = soundPool?.load(context, selectResId, 1) ?: -1
            }
        } catch (e: Exception) {
            // Resources not found, will use tone generator
        }
    }

    private fun initializeToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
        } catch (e: Exception) {
            toneGenerator = null
        }
    }

    /**
     * Play sound when a piece is moved
     */
    fun playMoveSound() {
        if (!enabled) return

        if (moveSoundId != -1) {
            soundPool?.play(moveSoundId, 0.6f, 0.6f, 1, 0, 1.0f)
        } else {
            // Fallback: short pleasant tone
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        }
    }

    /**
     * Play sound when a piece is captured
     */
    fun playCaptureSound() {
        if (!enabled) return

        if (captureSoundId != -1) {
            soundPool?.play(captureSoundId, 0.7f, 0.7f, 1, 0, 1.0f)
        } else {
            // Fallback: double tone for capture
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        }
    }

    /**
     * Play sound when a check occurs
     */
    fun playCheckSound() {
        if (!enabled) return

        if (checkSoundId != -1) {
            soundPool?.play(checkSoundId, 0.8f, 0.8f, 1, 0, 1.0f)
        } else {
            // Fallback: warning tone
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
        }
    }

    /**
     * Play sound when game is over
     */
    fun playGameOverSound() {
        if (!enabled) return

        if (gameOverSoundId != -1) {
            soundPool?.play(gameOverSoundId, 0.9f, 0.9f, 1, 0, 1.0f)
        } else {
            // Fallback: completion tone
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
        }
    }

    /**
     * Play sound when a piece is selected
     */
    fun playSelectSound() {
        if (!enabled) return

        if (selectSoundId != -1) {
            soundPool?.play(selectSoundId, 0.4f, 0.4f, 1, 0, 1.0f)
        } else {
            // Fallback: soft click
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
        }
    }

    /**
     * Enable or disable sound effects
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Check if sounds are enabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Release all sound resources
     */
    fun release() {
        soundPool?.release()
        soundPool = null
        toneGenerator?.release()
        toneGenerator = null
    }
}
