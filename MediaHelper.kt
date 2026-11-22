package com.example.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

data class CompressedMediaResult(
    val base64: String,
    val fileName: String,
    val sizeBytes: Long,
)

data class DeviceContact(
    val name: String,
    val phone: String,
)

object MediaHelper {
    fun createCameraOutputUri(context: Context): Uri {
        val file = File(context.cacheDir, "wchat_cam_${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun compressImageToBase64(context: Context, uri: Uri, maxBytes: Int = 110_000): CompressedMediaResult? {
        return runCatching {
            val fileName = queryFileName(context, uri) ?: "photo_${System.currentTimeMillis()}.jpg"
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val rawBytes = inputStream.use { it.readBytes() }
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

            val maxDim = 960
            val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height).coerceAtLeast(1))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                original
            }

            var quality = 82
            var jpegBytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                jpegBytes = out.toByteArray()
                quality -= 12
            } while (jpegBytes.size > maxBytes && quality >= 28)

            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            CompressedMediaResult(b64, fileName, jpegBytes.size.toLong())
        }.getOrNull()
    }

    fun readDocumentPreview(context: Context, uri: Uri, maxInlineBytes: Int = 150_000): CompressedMediaResult? {
        return runCatching {
            val fileName = queryFileName(context, uri) ?: "document_${System.currentTimeMillis()}"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            val b64 = if (bytes.size <= maxInlineBytes) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                ""
            }
            CompressedMediaResult(b64, fileName, bytes.size.toLong())
        }.getOrNull()
    }

    fun saveAndOpenAttachment(context: Context, fileName: String, base64Data: String): Boolean {
        return runCatching {
            val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val target = File(downloadsDir, safeName)
            target.writeBytes(Base64.decode(base64Data, Base64.NO_WRAP))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            val mime = when {
                safeName.endsWith(".pdf", true) -> "application/pdf"
                safeName.endsWith(".jpg", true) || safeName.endsWith(".jpeg", true) -> "image/jpeg"
                safeName.endsWith(".png", true) -> "image/png"
                safeName.endsWith(".m4a", true) -> "audio/mp4"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun decodeBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    fun readDeviceContacts(context: Context): List<DeviceContact> {
        val list = mutableListOf<DeviceContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && list.size < 100) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val phone = if (phoneIdx >= 0) cursor.getString(phoneIdx) ?: "" else ""
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        list.add(DeviceContact(name, phone))
                    }
                }
            }
        }
        return list
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentCoordinates(context: Context): Pair<Double, Double>? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        cont.resume(location.latitude to location.longitude)
                    } else {
                        client.lastLocation.addOnSuccessListener { last ->
                            cont.resume(last?.let { it.latitude to it.longitude })
                        }.addOnFailureListener {
                            cont.resume(null)
                        }
                    }
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
    }

    fun openMapCoordinates(context: Context, lat: Double, lng: Double, label: String) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

class VoiceRecorderPlayer(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var player: MediaPlayer? = null

    fun startRecording(): Boolean {
        return runCatching {
            stopRecordingAndGetBase64()
            val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            currentFile = outFile
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(32000)
            mr.setAudioSamplingRate(16000)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            true
        }.getOrElse {
            recorder = null
            false
        }
    }

    fun stopRecordingAndGetBase64(): CompressedMediaResult? {
        val mr = recorder ?: return null
        recorder = null
        return runCatching {
            mr.stop()
            mr.release()
            val file = currentFile ?: return null
            val bytes = file.readBytes()
            val capped = if (bytes.size <= 180_000) bytes else bytes.copyOf(180_000)
            CompressedMediaResult(
                base64 = Base64.encodeToString(capped, Base64.NO_WRAP),
                fileName = file.name,
                sizeBytes = capped.size.toLong(),
            )
        }.getOrNull()
    }

    fun playBase64(base64: String, onComplete: () -> Unit) {
        stopPlayback()
        runCatching {
            val temp = File(context.cacheDir, "play_${System.currentTimeMillis()}.m4a")
            temp.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
            player = MediaPlayer().apply {
                setDataSource(temp.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                    onComplete()
                }
                start()
            }
        }.onFailure { onComplete() }
    }

    fun stopPlayback() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }
}
