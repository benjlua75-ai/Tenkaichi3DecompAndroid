package com.dfdx047.dragonrage.voice

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/** A voice frame that arrived: who sent it, its sequence number and the encoded audio. */
class VoicePacket(val sender: Int, val seq: Int, val payload: ByteArray)

interface VoiceTransport {
    /** Sends one encoded frame. Must never block the capture thread for long. */
    fun sendVoice(seq: Int, payload: ByteArray)

    /** Waits up to ~100 ms for the next packet; null if none came. */
    fun receive(): VoicePacket?

    fun close()
}

/**
 * UDP to voice-relay.mjs. Packet layout (big endian):
 *   [0]      0x56 magic
 *   [1]      type: 0 = voice, 1 = ping (registers our address with the relay)
 *   [2..9]   room id (long): everybody in the same room hears each other
 *   [10..13] sender id (int)
 *   [14..15] sequence (16 bit, wraps)
 *   [16..]   audio (µ-law, one 20 ms frame)
 */
class UdpVoiceTransport(
    host: String,
    private val port: Int,
    private val roomId: Long,
    private val selfId: Int,
) : VoiceTransport {
    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket().apply { soTimeout = 100 }
    private val recvBuf = ByteArray(MAX_PACKET)
    @Volatile private var open = true

    private val pinger = Thread({
        while (open) {
            try { sendRaw(TYPE_PING, 0, ByteArray(0)) } catch (_: Exception) {}
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
        }
    }, "voice-ping").apply { isDaemon = true; start() }

    override fun sendVoice(seq: Int, payload: ByteArray) {
        if (!open) return
        try { sendRaw(TYPE_VOICE, seq, payload) } catch (_: Exception) {}
    }

    private fun sendRaw(type: Int, seq: Int, payload: ByteArray) {
        val b = ByteBuffer.allocate(HEADER + payload.size)
        b.put(MAGIC).put(type.toByte()).putLong(roomId).putInt(selfId).putShort(seq.toShort()).put(payload)
        socket.send(DatagramPacket(b.array(), b.position(), address, port))
    }

    override fun receive(): VoicePacket? {
        if (!open) return null
        return try {
            val p = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(p)
            if (p.length <= HEADER || recvBuf[0] != MAGIC || recvBuf[1].toInt() != TYPE_VOICE) return null
            val b = ByteBuffer.wrap(recvBuf, 0, p.length)
            b.position(10)
            val sender = b.int
            if (sender == selfId) return null
            val seq = b.short.toInt() and 0xFFFF
            VoicePacket(sender, seq, recvBuf.copyOfRange(HEADER, p.length))
        } catch (_: java.net.SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        open = false
        pinger.interrupt()
        socket.close()
    }

    private companion object {
        const val MAGIC: Byte = 0x56
        const val TYPE_VOICE = 0
        const val TYPE_PING = 1
        const val HEADER = 16
        const val MAX_PACKET = 1400
    }
}
