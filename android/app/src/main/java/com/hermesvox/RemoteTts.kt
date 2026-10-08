package com.hermesvox

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Remote-TTS prefs (user-supplied, blank by default — reply text never leaves
 *  the device until the user picks the remote engine AND saves a URL). */
internal const val KEY_TTS_REMOTE_URL = "tts_remote_url"
internal const val KEY_TTS_REMOTE_MODEL = "tts_remote_model"
internal const val KEY_TTS_REMOTE_VOICE = "tts_remote_voice"
internal const val KEY_TTS_REMOTE_KEY = "tts_remote_key"
internal const val DEFAULT_REMOTE_TTS_MODEL = "voxtral-mini-tts-latest"
internal const val DEFAULT_REMOTE_TTS_VOICE = "fr_marie_happy"

/** Pure: the speech POST target for a configured base. Null when unconfigured. */
internal fun ttsSpeechUrl(base: String): String? =
    if (base.isBlank()) null else "${base}/audio/speech"

/** Pure (unit-testable): the JSON body of one speech request. Minimal — model,
 *  voice, input — so any OpenAI-compatible /v1/audio/speech server accepts it;
 *  response_format wav because MediaPlayer plays WAV without a container probe. */
internal fun ttsSpeechBody(model: String, voice: String, input: String): String {
    fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    return "{\"model\":\"${esc(model)}\",\"voice\":\"${esc(voice)}\",\"input\":\"${esc(input)}\",\"response_format\":\"wav\"}"
}

/**
 * RemoteTts — an OpenAI-compatible `POST {base}/.../audio/speech` leg (Mistral
 * Voxtral TTS, an OpenAI TTS server, ...). Same VoxTts seam as SystemTts: a
 * one-shot speak() of the full reply, no streaming — the controller's
 * non-streaming path drives it exactly like the system engine. Reply text
 * leaves the device ONLY when the user supplied a URL in Settings.
 *
 * PRIVACY: mirrors RemoteStt — success logs length only, failures log the
 * error class/reason, never the echoed request body.
 */
class RemoteTts(private val context: Context) : VoxTts {
    private val prefs get() = context.getSharedPreferences("hv", Context.MODE_PRIVATE)
    private fun prefString(k: String, d: String) = prefs.getString(k, d) ?: d

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var speakGen = 0

    override val name get() = "remote"
    override val isWarm get() = false
    override val warmReason get() = "stateless HTTP — configured when the URL is set"
    override val supportsStreaming get() = false

    /** Stateless HTTP: ready as soon as a URL is configured. Each speak() is its
     *  own attempt — a dead server fails that turn's audio (onDone still fires,
     *  so the loop is never stuck), not the whole pipeline. */
    override fun init(onReady: (Boolean) -> Unit) {
        val ok = sttBaseUrl(prefString(KEY_TTS_REMOTE_URL, "")).isNotBlank()
        VoxLog.d("event=tts-remote-init ok=$ok")
        onReady(ok)
    }

    /** Optional Bearer key, decrypted on demand from SecureStore (never prefs-in-clear). */
    private fun apiKey(): String {
        val raw = (prefs.getString(KEY_TTS_REMOTE_KEY, null) ?: "").trim()
        if (raw.isEmpty()) return ""
        return SecureStore.decrypt(raw) ?: ""
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val base = sttBaseUrl(prefString(KEY_TTS_REMOTE_URL, ""))
        val endpoint = ttsSpeechUrl(base)
        if (endpoint == null) { onDone(); return }
        val model = prefString(KEY_TTS_REMOTE_MODEL, DEFAULT_REMOTE_TTS_MODEL).ifBlank { DEFAULT_REMOTE_TTS_MODEL }
        val voice = prefString(KEY_TTS_REMOTE_VOICE, DEFAULT_REMOTE_TTS_VOICE).ifBlank { DEFAULT_REMOTE_TTS_VOICE }
        val key = apiKey()
        val myGen = ++speakGen
        thread {
            val tmp = File(context.cacheDir, "tts-remote-$myGen.wav")
            try {
                val body = ttsSpeechBody(model, voice, text).toByteArray(Charsets.UTF_8)
                val t0 = android.os.SystemClock.uptimeMillis()
                val c = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.connectTimeout = 8000
                    c.readTimeout = 20000
                    c.doOutput = true
                    c.setRequestProperty("Accept", "audio/*")
                    c.setRequestProperty("Content-Type", "application/json")
                    if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    if (code !in 200..299) {
                        try { c.errorStream?.close() } catch (_: Throwable) {}
                        VoxLog.w("event=tts-remote err=http-$code")
                        onDone()
                        return@thread
                    }
                    c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                } finally { c.disconnect() }
                if (myGen != speakGen) { tmp.delete(); onDone(); return@thread }   // superseded while synthesizing
                val ms = android.os.SystemClock.uptimeMillis() - t0
                VoxLog.dd("event=tts-remote ms=$ms textLen=${text.length} bytes=${tmp.length()}")
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(tmp.absolutePath)
                    mp.setOnCompletionListener {
                        try { it.release() } catch (_: Throwable) {}
                        tmp.delete()
                        if (myGen == speakGen) onDone()
                    }
                    mp.prepare()
                    player = mp
                    mp.start()
                } catch (e: Throwable) {
                    VoxLog.w("event=tts-remote err=${e.message?.take(120)}")
                    try { mp.release() } catch (_: Throwable) {}
                    tmp.delete()
                    onDone()
                }
            } catch (e: Throwable) {
                VoxLog.w("event=tts-remote err=${e.message?.take(120)}")
                tmp.delete()
                onDone()
            }
        }
    }

    override fun stop() {
        speakGen++
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        // Leave no half-synthesized files behind; they are cache-ephemeral anyway.
        try { context.cacheDir.listFiles()?.filter { it.name.startsWith("tts-remote-") }?.forEach { it.delete() } }
        catch (_: Throwable) {}
    }

    override fun shutdown() { stop() }
}
