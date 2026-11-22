package com.xeylabs.vmusic.data.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioEffectPreset(
    val bandLevels: IntArray,
    val bassBoost: Int,
    val virtualizer: Int,
    val loudnessGain: Int
) {
    FLAT(intArrayOf(0, 0, 0, 0, 0), 0, 0, 0),
    SPATIAL(intArrayOf(300, 150, -100, 200, 350), 350, 650, 100),
    BASS(intArrayOf(600, 450, 0, -150, -250), 800, 0, 0),
    VOCAL(intArrayOf(-250, 150, 500, 400, 150), 0, 0, 0),
    TREBLE(intArrayOf(-200, -100, 50, 400, 600), 0, 0, 0),
    CUSTOM(intArrayOf(0, 0, 0, 0, 0), 0, 0, 0)
}

class AudioEffectsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _preset = MutableStateFlow(loadPreset())
    val preset: StateFlow<AudioEffectPreset> = _preset.asStateFlow()

    private val _bandLevels = MutableStateFlow(loadBandLevels())
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()

    private val _bassStrength = MutableStateFlow(prefs.getInt(KEY_BASS, 0).coerceIn(0, 1000))
    val bassStrength: StateFlow<Int> = _bassStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(prefs.getInt(KEY_VIRTUALIZER, 0).coerceIn(0, 1000))
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _equalizerSupported = MutableStateFlow(true)
    val equalizerSupported: StateFlow<Boolean> = _equalizerSupported.asStateFlow()

    private val _bassSupported = MutableStateFlow(true)
    val bassSupported: StateFlow<Boolean> = _bassSupported.asStateFlow()

    private val _virtualizerSupported = MutableStateFlow(true)
    val virtualizerSupported: StateFlow<Boolean> = _virtualizerSupported.asStateFlow()

    private var loudnessGain: Int = prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, 500)

    private var sessionId: Int = -1
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bandMin: Int = BAND_MIN_MILLIBELS
    private var bandMax: Int = BAND_MAX_MILLIBELS
    private var bandCount: Int = BAND_COUNT

    private fun loadPreset(): AudioEffectPreset {
        val name = prefs.getString(KEY_PRESET, AudioEffectPreset.FLAT.name) ?: AudioEffectPreset.FLAT.name
        return try {
            AudioEffectPreset.valueOf(name)
        } catch (_: Exception) {
            AudioEffectPreset.FLAT
        }
    }

    private fun loadBandLevels(): List<Int> {
        val raw = prefs.getString(KEY_BANDS, null)
        val parsed = raw?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (parsed != null && parsed.size == BAND_COUNT) {
            return parsed.map { it.coerceIn(BAND_MIN_MILLIBELS, BAND_MAX_MILLIBELS) }
        }
        return _preset.value.bandLevels.toList()
    }

    private fun persistAll() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, _enabled.value)
            .putString(KEY_PRESET, _preset.value.name)
            .putString(KEY_BANDS, _bandLevels.value.joinToString(","))
            .putInt(KEY_BASS, _bassStrength.value)
            .putInt(KEY_VIRTUALIZER, _virtualizerStrength.value)
            .putInt(KEY_LOUDNESS, loudnessGain)
            .apply()
    }

    fun attachToSession(newSessionId: Int) {
        if (newSessionId <= 0) return
        if (newSessionId == sessionId && equalizer != null) {
            applyAll()
            return
        }
        releaseEffects()
        sessionId = newSessionId
        equalizer = runCatching { Equalizer(0, newSessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, newSessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, newSessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(newSessionId) }.getOrNull()
        _equalizerSupported.value = equalizer != null
        _bassSupported.value = bassBoost?.let {
            runCatching { it.strengthSupported }.getOrDefault(false)
        } ?: false
        _virtualizerSupported.value = virtualizer?.let {
            runCatching { it.strengthSupported }.getOrDefault(false)
        } ?: false
        equalizer?.let { eq ->
            bandMin = runCatching { eq.bandLevelRange[0].toInt() }.getOrDefault(BAND_MIN_MILLIBELS)
            bandMax = runCatching { eq.bandLevelRange[1].toInt() }.getOrDefault(BAND_MAX_MILLIBELS)
            bandCount = runCatching { eq.numberOfBands.toInt() }.getOrDefault(BAND_COUNT)
        }
        applyAll()
    }

    private fun applyAll() {
        val on = _enabled.value
        equalizer?.let { eq ->
            val levels = _bandLevels.value
            for (i in 0 until minOf(bandCount, levels.size)) {
                runCatching { eq.setBandLevel(i.toShort(), levels[i].coerceIn(bandMin, bandMax).toShort()) }
            }
            runCatching { eq.enabled = on }
        }
        bassBoost?.let { bass ->
            runCatching { bass.setStrength(_bassStrength.value.toShort()) }
            runCatching { bass.enabled = on }
        }
        virtualizer?.let { virt ->
            runCatching { virt.setStrength(_virtualizerStrength.value.toShort()) }
            runCatching { virt.enabled = on }
        }
        loudnessEnhancer?.let { loud ->
            runCatching { loud.setTargetGain(if (on) loudnessGain else 0) }
            runCatching { loud.enabled = on && loudnessGain > 0 }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persistAll()
        applyAll()
    }

    fun setPreset(preset: AudioEffectPreset) {
        if (preset == AudioEffectPreset.CUSTOM) return
        _preset.value = preset
        _bandLevels.value = preset.bandLevels.toList()
        _bassStrength.value = preset.bassBoost
        _virtualizerStrength.value = preset.virtualizer
        loudnessGain = preset.loudnessGain
        persistAll()
        applyAll()
    }

    fun setBandLevel(index: Int, levelMillibels: Int) {
        if (index !in 0 until BAND_COUNT) return
        val levels = _bandLevels.value.toMutableList()
        levels[index] = levelMillibels.coerceIn(BAND_MIN_MILLIBELS, BAND_MAX_MILLIBELS)
        _bandLevels.value = levels
        markCustom()
        persistAll()
        applyAll()
    }

    fun setBassStrength(strength: Int) {
        _bassStrength.value = strength.coerceIn(0, 1000)
        markCustom()
        persistAll()
        applyAll()
    }

    fun setVirtualizerStrength(strength: Int) {
        _virtualizerStrength.value = strength.coerceIn(0, 1000)
        markCustom()
        persistAll()
        applyAll()
    }

    private fun markCustom() {
        if (_preset.value != AudioEffectPreset.CUSTOM) {
            _preset.value = AudioEffectPreset.CUSTOM
        }
    }

    fun release() {
        releaseEffects()
        sessionId = -1
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }

    companion object {
        private const val PREF_NAME = "vmusic_audio_effects"
        private const val KEY_ENABLED = "effects_enabled"
        private const val KEY_PRESET = "effects_preset"
        private const val KEY_BANDS = "effects_bands"
        private const val KEY_BASS = "effects_bass"
        private const val KEY_VIRTUALIZER = "effects_virtualizer"
        private const val KEY_LOUDNESS = "effects_loudness"
        const val BAND_COUNT = 5
        const val BAND_MIN_MILLIBELS = -1500
        const val BAND_MAX_MILLIBELS = 1500

        @Volatile
        private var instance: AudioEffectsManager? = null

        fun getInstance(context: Context): AudioEffectsManager {
            return instance ?: synchronized(this) {
                val safeCtx = context.applicationContext ?: context
                instance ?: AudioEffectsManager(safeCtx).also { instance = it }
            }
        }
    }
}
