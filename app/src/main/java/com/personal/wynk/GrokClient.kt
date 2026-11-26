package com.personal.wynk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal xAI chat client: POST /v1/chat/completions with stream=true, read the server-sent
 * events, and hand each text delta to [onDelta]. Uses only the platform's HttpURLConnection and
 * org.json, so there are no extra dependencies.
 */
class GrokClient(
    private val apiKey: String,
    private val model: String = "grok-4.7",
    // grok-4.7 reasons before answering. "low" cuts the first words from ~11 s to ~3 s,
    // which matters when you're waiting on the glasses.
    private val reasoningEffort: String? = "low",
) {
  class GrokException(message: String) : IOException(message)

  /** Streams one reply. Returns the full text. Cancelling the coroutine closes the connection. */
  suspend fun stream(messages: JSONArray, onDelta: (String) -> Unit): String =
      withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GrokException("No xai_api_key in local.properties")
        val body =
            JSONObject()
                .put("model", model)
                .put("stream", true)
                .apply { reasoningEffort?.let { put("reasoning_effort", it) } }
                .put("messages", messages)
                .toString()

        val conn = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
        try {
          conn.requestMethod = "POST"
          conn.connectTimeout = 15_000
          conn.readTimeout = 60_000
          conn.doOutput = true
          conn.setRequestProperty("Authorization", "Bearer $apiKey")
          conn.setRequestProperty("Content-Type", "application/json")
          conn.setRequestProperty("Accept", "text/event-stream")
          conn.outputStream.use { it.write(body.toByteArray()) }

          val code = conn.responseCode
          if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw GrokException("Grok error $code: ${errorMessage(err)}")
          }

          val full = StringBuilder()
          conn.inputStream.bufferedReader().use { reader ->
            while (true) {
              currentCoroutineContext().ensureActive()
              val line = reader.readLine() ?: break
              if (!line.startsWith("data:")) continue
              val data = line.removePrefix("data:").trim()
              if (data == "[DONE]") break
              val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: continue
              val delta = choice.optJSONObject("delta")?.optString("content").orEmpty()
              if (delta.isNotEmpty()) {
                full.append(delta)
                onDelta(delta)
              }
            }
          }
          full.toString()
        } finally {
          conn.disconnect()
        }
      }

  /** Speech-to-text: POST /v1/stt (multipart). Returns the transcript, possibly empty. */
  suspend fun transcribe(wav: ByteArray): String =
      withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GrokException("No xai_api_key in local.properties")
        val boundary = "wynk-${System.currentTimeMillis()}"
        val conn = URL("$BASE/stt").openConnection() as HttpURLConnection
        try {
          conn.requestMethod = "POST"
          conn.connectTimeout = 15_000
          conn.readTimeout = 30_000
          conn.doOutput = true
          conn.setRequestProperty("Authorization", "Bearer $apiKey")
          conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
          conn.outputStream.use { out ->
            // Option fields must come before the file field.
            out.write(
                ("--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                        "$STT_MODEL\r\n" +
                        "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                        "Content-Type: audio/wav\r\n\r\n")
                    .toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
          }
          val code = conn.responseCode
          if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw GrokException("Speech-to-text error $code: ${errorMessage(err)}")
          }
          val body = conn.inputStream.bufferedReader().use { it.readText() }
          JSONObject(body).optString("text").trim()
        } finally {
          conn.disconnect()
        }
      }

  private fun errorMessage(body: String): String =
      runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("error").takeIf { it.isNotBlank() }
                ?: body
          }
          .getOrDefault(body)
          .take(300)

  companion object {
    private const val BASE = "https://api.x.ai/v1"
    private const val STT_MODEL = "grok-voice-transcribe-2.0"
  }
}
