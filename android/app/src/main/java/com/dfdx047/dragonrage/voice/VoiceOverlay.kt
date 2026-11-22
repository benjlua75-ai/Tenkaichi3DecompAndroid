package com.dfdx047.dragonrage.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.security.SecureRandom

/**
 * Turns voice chat on from a file, so the engine needs no changes: `voice.txt` in the game's files folder
 * (Android/data/com.dfdx047.dragonrage/files/voice.txt), three lines:
 *
 *     your.server.com:7777     the relay (voice-relay.mjs): host:port
 *     myroom123                the room: everybody who writes the same word hears each other
 *     ptt                      "ptt" = hold the button to talk; "open" = open microphone, the button mutes
 *
 * No file: nothing happens. With it: asks for the microphone once, then shows a round button in the top left corner.
 */
class VoiceOverlay(private val activity: Activity) {
    private class Config(val host: String, val port: Int, val room: String, val open: Boolean)

    private var config: Config? = null
    private var chat: VoiceChat? = null
    private var button: TextView? = null
    @Volatile private var stopped = false

    fun start() {
        val c = readConfig() ?: return
        config = c
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(c)
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
        }
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST) return
        val c = config ?: return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) begin(c)
    }

    fun stop() {
        stopped = true
        chat?.stop()
        chat = null
    }

    private fun begin(c: Config) {
        addButton(c)
        // The socket resolves the host name: not on the UI thread.
        Thread({
            try {
                val transport = UdpVoiceTransport(c.host, c.port, roomId(c.room), SecureRandom().nextInt())
                val vc = VoiceChat(transport)
                vc.mode = if (c.open) VoiceChat.Mode.OPEN_MIC else VoiceChat.Mode.PUSH_TO_TALK
                if (stopped) { transport.close(); return@Thread }
                chat = vc
                vc.start()
            } catch (e: Exception) {
                Log.w(TAG, "voice chat did not start: $e")
            }
        }, "voice-start").start()
    }

    private fun addButton(c: Config) {
        val size = (56 * activity.resources.displayMetrics.density).toInt()
        val margin = (12 * activity.resources.displayMetrics.density).toInt()
        val b = TextView(activity).apply {
            text = "MIC"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.75f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#444444")) }
        }
        fun paint(on: Boolean) {
            (b.background as GradientDrawable).setColor(Color.parseColor(if (on) "#2E9E4F" else "#444444"))
        }
        if (c.open) {
            paint(true)
            b.setOnClickListener {
                val vc = chat ?: return@setOnClickListener
                val on = vc.mode == VoiceChat.Mode.OFF
                vc.mode = if (on) VoiceChat.Mode.OPEN_MIC else VoiceChat.Mode.OFF
                paint(on)
            }
        } else {
            b.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { chat?.talking = true; paint(true) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { chat?.talking = false; paint(false) }
                }
                true
            }
        }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
            setMargins(margin, margin, margin, margin)
        }
        activity.addContentView(b, lp)
        button = b
    }

    private fun readConfig(): Config? = try {
        val dir = activity.getExternalFilesDir(null) ?: activity.filesDir
        val lines = File(dir, "voice.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (lines == null || lines.size < 2) null else {
            val hp = lines[0].split(":")
            Config(hp[0], hp.getOrNull(1)?.toIntOrNull() ?: 7777, lines[1], lines.getOrNull(2).equals("open", ignoreCase = true))
        }
    } catch (e: Exception) {
        Log.w(TAG, "voice.txt: $e"); null
    }

    /** FNV-1a, 64 bit: the room word becomes the number the relay separates rooms by. */
    private fun roomId(room: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (ch in room.toByteArray()) { h = h xor (ch.toLong() and 0xFF); h *= 0x100000001b3L }
        return h
    }

    private companion object {
        const val TAG = "VoiceOverlay"
        const val REQUEST = 7701
    }
}
