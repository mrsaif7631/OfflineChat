package com.offlinechat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

object Media {
    /** Keep media small: Bluetooth/Wi-Fi Direct between phones is slower than the internet. */
    const val MAX_BYTES = 900_000

    private fun dir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }

    fun save(ctx: Context, id: String, kind: Kind, bytes: ByteArray): String {
        val ext = if (kind == Kind.IMAGE) "jpg" else "m4a"
        val f = File(dir(ctx), "$id.$ext")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** Load a photo from the gallery, shrink it to max 1280 px, and compress it to JPEG. */
    fun loadImageForSend(ctx: Context, uri: Uri): ByteArray? = try {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, 1280f / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        bmp?.let {
            var quality = 75
            var out: ByteArray
            do {
                val bos = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                out = bos.toByteArray()
                quality -= 15
            } while (out.size > MAX_BYTES && quality > 20)
            out.takeIf { o -> o.size <= MAX_BYTES }
        }
    } catch (e: Exception) {
        null
    }

    fun decodeScaled(path: String, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

/** Records small AAC voice notes (about 3 KB per second). Max 2 minutes. */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = try {
        val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(24_000)
        r.setAudioChannels(1)
        r.setMaxDuration(120_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }

    /** Returns (audio bytes, duration ms), or null if too short or failed. */
    fun stop(): Pair<ByteArray, Int>? {
        val r = recorder ?: return null
        recorder = null
        val duration = (System.currentTimeMillis() - startedAt).toInt().coerceAtMost(120_000)
        return try {
            r.stop()
            r.release()
            val f = file ?: return null
            val bytes = f.readBytes()
            f.delete()
            if (duration < 700 || bytes.size > Media.MAX_BYTES) null else bytes to duration
        } catch (e: Exception) {
            r.release()
            null
        }
    }

    fun cancel() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        file?.delete()
    }
}

object VoicePlayer {
    var playingPath by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(path: String) {
        if (playingPath == path) { stop(); return }
        stop()
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { VoicePlayer.stop() }
                prepare()
                start()
            }
            playingPath = path
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.release()
        player = null
        playingPath = null
    }
}
