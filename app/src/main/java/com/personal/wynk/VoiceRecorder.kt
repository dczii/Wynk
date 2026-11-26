package com.personal.wynk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records speech from the glasses microphone. The glasses reach the phone as a Bluetooth HFP
 * (SCO) headset, so this routes communication audio to them, records with AudioRecord, then puts
 * the route back. With no Bluetooth headset it falls back to the phone's own mic.
 */
class VoiceRecorder(private val context: Context) {
  private val audio = context.getSystemService(AudioManager::class.java)

  fun hasPermission(): Boolean =
      context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  /**
   * Records until [keepGoing] returns false or [maxMillis] passes. [onStart] reports whether the
   * glasses mic is in use. Returns a 16-bit mono WAV, or null if the mic could not start.
   */
  @SuppressLint("MissingPermission")
  suspend fun record(
      keepGoing: () -> Boolean,
      onStart: (usingGlasses: Boolean) -> Unit,
      maxMillis: Long = 60_000,
  ): ByteArray? =
      withContext(Dispatchers.IO) {
        val glasses =
            audio.availableCommunicationDevices.firstOrNull {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        val previousMode = audio.mode
        var routed = false
        if (glasses != null) {
          audio.mode = AudioManager.MODE_IN_COMMUNICATION
          routed = audio.setCommunicationDevice(glasses)
          // Let the SCO link come up before recording, or the first second is silence.
          if (routed) {
            withTimeoutOrNull(3_000) {
              while (audio.communicationDevice?.id != glasses.id) delay(50)
            }
            delay(300)
          }
        }
        Log.i(TAG, "Mic: ${if (routed) "glasses (${glasses?.productName})" else "phone"}")

        val rate = SAMPLE_RATE
        val minBuffer =
            AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        try {
          if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null
          recorder.startRecording()
          onStart(routed)
          val pcm = ByteArrayOutputStream()
          val buffer = ByteArray(minBuffer)
          val started = System.currentTimeMillis()
          while (keepGoing() && System.currentTimeMillis() - started < maxMillis) {
            currentCoroutineContext().ensureActive()
            val n = recorder.read(buffer, 0, buffer.size)
            if (n > 0) pcm.write(buffer, 0, n)
          }
          wav(pcm.toByteArray(), rate)
        } finally {
          runCatching { recorder.stop() }
          recorder.release()
          if (routed) audio.clearCommunicationDevice()
          audio.mode = previousMode
        }
      }

  /** Wraps raw 16-bit mono PCM in a 44-byte WAV header. */
  private fun wav(pcm: ByteArray, rate: Int): ByteArray {
    val header =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
          put("RIFF".toByteArray())
          putInt(36 + pcm.size)
          put("WAVE".toByteArray())
          put("fmt ".toByteArray())
          putInt(16) // fmt chunk size
          putShort(1) // PCM
          putShort(1) // mono
          putInt(rate)
          putInt(rate * 2) // byte rate
          putShort(2) // block align
          putShort(16) // bits per sample
          put("data".toByteArray())
          putInt(pcm.size)
        }
    return header.array() + pcm
  }

  companion object {
    private const val TAG = "Wynk"
    // HFP carries 8 or 16 kHz; 16 kHz lets Android use wideband when the glasses offer it.
    private const val SAMPLE_RATE = 16_000
  }
}
