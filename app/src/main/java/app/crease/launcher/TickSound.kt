package app.crease.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock

/**
 * Plays the home styles' clicks. Each kind keeps a few ready-made tracks and takes turns, so
 * quick clicks don't cut each other off. Silent and vibrate modes mute it.
 */
class TickSound(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pools = HashMap<TickKind, List<AudioTrack>>()
    private val next = HashMap<TickKind, Int>()
    private var lastAt = 0L

    private fun pool(kind: TickKind): List<AudioTrack> = pools.getOrPut(kind) {
        val samples = tickSamples(kind, RATE)
        List(VOICES) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { it.write(samples, 0, samples.size) }
        }
    }

    fun play(kind: TickKind) {
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        // A fast flick passes many apps at once; spaced clicks sound like a reel, not a buzz.
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < MIN_GAP_MS) return
        lastAt = now
        runCatching {
            val tracks = pool(kind)
            val i = next.getOrDefault(kind, 0)
            next[kind] = (i + 1) % tracks.size
            tracks[i].apply {
                stop()
                reloadStaticData()
                play()
            }
        }
    }

    fun release() {
        pools.values.flatten().forEach { runCatching { it.release() } }
        pools.clear()
    }

    private companion object {
        const val RATE = 44100
        const val VOICES = 3
        const val MIN_GAP_MS = 35L
    }
}
