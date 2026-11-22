package com.dfdx047.dragonrage.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * In-game voice chat, independent of the engine: microphone -> µ-law -> [VoiceTransport] -> jitter buffer -> mixer -> speaker.
 * 8 kHz mono, 20 ms frames (160 bytes, ~64 kbit/s while talking). The caller must hold RECORD_AUDIO before [start].
 */
class VoiceChat(private val transport: VoiceTransport) {
    enum class Mode { OFF, PUSH_TO_TALK, OPEN_MIC }

    @Volatile var mode = Mode.PUSH_TO_TALK
    /** Held down while the player presses the talk button (PUSH_TO_TALK). */
    @Volatile var talking = false
    /** 0..2; 1 = unchanged. */
    @Volatile var volume = 1f
    /** OPEN_MIC: frames quieter than this (RMS, 0..32767) are not sent. */
    @Volatile var gate = 500
    /** Called with the sender id when somebody starts or stops being heard (for a speaking icon). Runs on the playback thread. */
    @Volatile var onSpeaking: ((sender: Int, speaking: Boolean) -> Unit)? = null

    private val muted = ConcurrentHashMap<Int, Boolean>()
    private val peers = ConcurrentHashMap<Int, Peer>()
    @Volatile private var running = false
    private var threads: List<Thread> = emptyList()

    private class Peer {
        val frames = java.util.ArrayDeque<ByteArray>()
        var lastSeq = -1
        var playing = false
        var heard = false
    }

    fun setMuted(sender: Int, mute: Boolean) { if (mute) muted[sender] = true else muted.remove(sender) }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        threads = listOf(
            Thread({ captureLoop() }, "voice-capture"),
            Thread({ receiveLoop() }, "voice-recv"),
            Thread({ playbackLoop() }, "voice-play"),
        ).onEach { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        threads.forEach { it.interrupt() }
        threads = emptyList()
        transport.close()
        peers.clear()
    }

    // ---- microphone -> network ----

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 8),
            )
        } catch (e: Exception) {
            Log.w(TAG, "no microphone: $e"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "microphone not ready"); rec.release(); return }
        // Echo and noise cancelling, where the phone has them: without them the speaker feeds the microphone.
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true

        val pcm = ShortArray(FRAME)
        var seq = 0
        var hang = 0
        rec.startRecording()
        try {
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(pcm, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) continue
                val send = when (mode) {
                    Mode.OFF -> false
                    Mode.PUSH_TO_TALK -> talking
                    Mode.OPEN_MIC -> {
                        if (rms(pcm) >= gate) hang = HANGOVER else if (hang > 0) hang--
                        hang > 0
                    }
                }
                if (!send) continue
                val out = ByteArray(FRAME) { mulawEncode(pcm[it].toInt()) }
                transport.sendVoice(seq, out)
                seq = (seq + 1) and 0xFFFF
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    // ---- network -> jitter buffers ----

    private fun receiveLoop() {
        while (running) {
            val p = transport.receive() ?: continue
            if (p.payload.size != FRAME || muted.containsKey(p.sender)) continue
            val peer = peers.getOrPut(p.sender) { Peer() }
            synchronized(peer) {
                if (peer.lastSeq >= 0 && seqOlder(p.seq, peer.lastSeq)) return@synchronized // late or duplicate
                // a gap of lost packets: pad with a silent frame so the timing stays right (at most one)
                if (peer.lastSeq >= 0 && ((p.seq - peer.lastSeq) and 0xFFFF) > 1) peer.frames.add(ByteArray(FRAME) { 0xFF.toByte() })
                peer.lastSeq = p.seq
                peer.frames.add(p.payload)
                while (peer.frames.size > MAX_QUEUE) peer.frames.poll() // too far behind: jump ahead
            }
        }
    }

    // ---- jitter buffers -> speaker (the blocking write paces the loop at 20 ms per frame) ----

    private fun playbackLoop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val mix = IntArray(FRAME)
        val out = ShortArray(FRAME)
        track.play()
        try {
            while (running) {
                mix.fill(0)
                for ((id, peer) in peers) {
                    var frame: ByteArray? = null
                    synchronized(peer) {
                        if (!peer.playing && peer.frames.size >= PREBUFFER) peer.playing = true
                        if (peer.playing) {
                            frame = peer.frames.poll()
                            if (frame == null) peer.playing = false // ran dry: rebuffer
                        }
                    }
                    val f = frame
                    if (f != null) {
                        for (i in 0 until FRAME) mix[i] += mulawDecode(f[i].toInt() and 0xFF)
                    }
                    val speaking = f != null
                    if (speaking != peer.heard) { peer.heard = speaking; onSpeaking?.invoke(id, speaking) }
                }
                val v = volume
                for (i in 0 until FRAME) out[i] = (mix[i] * v).toInt().coerceIn(-32768, 32767).toShort()
                track.write(out, 0, FRAME)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ---- helpers ----

    private fun rms(pcm: ShortArray): Int {
        var s = 0L
        for (x in pcm) s += x.toLong() * x
        return sqrt(s.toDouble() / pcm.size).toInt()
    }

    /** True if a comes before b on the wrapping 16-bit sequence (or equals it). */
    private fun seqOlder(a: Int, b: Int): Boolean = ((a - b) and 0xFFFF) >= 0x8000 || a == b

    companion object {
        private const val TAG = "VoiceChat"
        const val RATE = 8000
        const val FRAME = 160          // 20 ms
        private const val PREBUFFER = 3  // 60 ms before a speaker starts playing
        private const val MAX_QUEUE = 10 // 200 ms
        private const val HANGOVER = 10  // 200 ms of tail after the voice stops (OPEN_MIC)

        // G.711 µ-law
        private const val BIAS = 0x84
        private const val CLIP = 32635

        fun mulawEncode(sample: Int): Byte {
            var s = sample
            val sign = if (s < 0) { s = -s; 0x80 } else 0
            if (s > CLIP) s = CLIP
            s += BIAS
            var exp = 7
            var mask = 0x4000
            while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
            val mant = (s shr (exp + 3)) and 0x0F
            return (sign or (exp shl 4) or mant).inv().toByte()
        }

        fun mulawDecode(code: Int): Int {
            val u = code.inv() and 0xFF
            val exp = (u shr 4) and 0x07
            val mant = u and 0x0F
            val s = (((mant shl 3) + BIAS) shl exp) - BIAS
            return if (u and 0x80 != 0) -s else s
        }
    }
}
