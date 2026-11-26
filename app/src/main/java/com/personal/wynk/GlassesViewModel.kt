package com.personal.wynk

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.types.PhotoCaptureData
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Wynk"

data class UiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val deviceCount: Int = 0,
    val sessionState: DeviceSessionState? = null,
    val displayState: DisplayState? = null,
    val useSimulator: Boolean = false,
    val capturing: Boolean = false,
    val captureProgress: Double? = null,
    val thinking: Boolean = false,
    val status: String = "Idle",
)

/** The last photo taken with the glasses camera. */
data class CapturedPhoto(val bitmap: Bitmap, val bytes: Int, val millis: Long)

enum class Role {
  USER,
  ASSISTANT,
}

/** One chat bubble. [image] is set on user messages that carried a photo. */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val image: Bitmap? = null,
    val streaming: Boolean = false,
    val failed: Boolean = false,
)

/** Keeps replies readable on the 600×600 glasses display. */
private const val SYSTEM_PROMPT =
    "You are Grok, talking to the user through Wynk, an app that shows your replies on Meta " +
        "Ray-Ban Display glasses with a small 600x600 screen. The user talks to you by voice or " +
        "by sending a photo from the glasses camera. Reply in plain text only: no markdown, " +
        "no headings, no bullet symbols, no tables, no emoji. Keep replies to about 60 words " +
        "unless the user asks for more detail. Only write code when asked, and keep code lines " +
        "short. When a photo is unclear, say what you can see and ask for a closer shot."

private const val DEFAULT_PHOTO_PROMPT = "What's in this photo?"

/**
 * Display and camera flows, following Meta's DisplayAccess and CameraAccess samples:
 * register -> create session -> session STARTED -> add a capability (display or camera).
 *
 * Meta's known issues say not to combine capture with display, so a capture removes the display,
 * takes one photo, stops the camera, then puts the display back.
 */
class GlassesViewModel(application: Application) : AndroidViewModel(application) {
  private val _ui = MutableStateFlow(UiState())
  val ui: StateFlow<UiState> = _ui.asStateFlow()

  private val _simGlasses = MutableStateFlow<MockGlasses?>(null)
  /** Simulated Meta Ray-Ban Display glasses, rendered in-app when the simulator is on. */
  val simGlasses: StateFlow<MockGlasses?> = _simGlasses.asStateFlow()

  private val _photo = MutableStateFlow<CapturedPhoto?>(null)
  val photo: StateFlow<CapturedPhoto?> = _photo.asStateFlow()

  private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
  /** The conversation, oldest first. The phone is the source of truth. */
  val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

  private val _pendingPhoto = MutableStateFlow<Bitmap?>(null)
  /** A photo waiting to go out with the next message (from the glasses or the gallery). */
  val pendingPhoto: StateFlow<Bitmap?> = _pendingPhoto.asStateFlow()

  private val grok = GrokClient(BuildConfig.XAI_API_KEY)
  private var replyJob: Job? = null
  private var nextId = 0L

  private var initialized = false
  private var session: DeviceSession? = null
  private var display: Display? = null
  private var displayJob: Job? = null
  private val sessionJobs = mutableListOf<Job>()

  /** What to draw whenever the display (re)starts or the chat changes. */
  private var currentScreen: () -> Unit = ::showChat

  /** Which page of the latest reply the glasses show. */
  private var replyPage = 0
  private var refreshJob: Job? = null
  private var lastScreenAt = 0L

  /** Call once Bluetooth permissions are granted. */
  fun initialize() {
    if (initialized) return
    initialized = true
    Wearables.initialize(getApplication())
    viewModelScope.launch {
      Wearables.registrationState.collect { s -> _ui.update { it.copy(registrationState = s) } }
    }
    viewModelScope.launch {
      Wearables.devices.collect { d -> _ui.update { it.copy(deviceCount = d.size) } }
    }
    // Logs link state, compatibility and display capability whenever a device changes.
    // Handy when createSession says "No eligible device available".
    viewModelScope.launch {
      val last = mutableMapOf<String, String>()
      while (true) {
        Wearables.devicesMetadata.forEach { (id, flow) ->
          val line = "${flow.value} displayCapable=${flow.value.isDisplayCapable()}"
          if (last.put(id.toString(), line) != line) Log.i(TAG, "Device $id: $line")
        }
        delay(2_000)
      }
    }
    viewModelScope.launch {
      Wearables.registrationErrorStream.collect { e ->
        status("Registration error: ${e.getLocalizedDescription(getApplication())}")
      }
    }
  }

  fun register(activity: Activity) = Wearables.startRegistration(activity)

  fun unregister(activity: Activity) = Wearables.startUnregistration(activity)

  fun setUseSimulator(on: Boolean) {
    stop()
    val kit = MockDeviceKit.getInstance(getApplication())
    if (on) {
      kit.enable()
      kit.pairGlasses(GlassesModel.META_RAYBAN_DISPLAY)
          .onSuccess { glasses ->
            glasses.powerOn()
            glasses.don()
            _simGlasses.value = glasses
            _ui.update { it.copy(useSimulator = true) }
            status("Simulator ready")
          }
          .onFailure { error, _ -> status("Simulator failed: $error") }
    } else {
      _simGlasses.value?.let {
        it.doff()
        it.powerOff()
      }
      _simGlasses.value = null
      kit.disable()
      _ui.update { it.copy(useSimulator = false) }
      status("Simulator off")
    }
  }

  // ---- Session ----

  /** Starts a session (if needed) and shows the current screen on the glasses. */
  fun start() {
    viewModelScope.launch {
      val s = ensureSession() ?: return@launch
      if (display == null) attachDisplay(s) else currentScreen()
    }
  }

  fun stop() {
    session?.let {
      it.removeDisplay()
      it.removeCamera()
      it.stop()
    }
    cleanup("Stopped")
  }

  /** Returns a STARTED session, creating one if needed, or null (with a status) on failure. */
  private suspend fun ensureSession(): DeviceSession? {
    session?.let { s ->
      if (s.state.value == DeviceSessionState.STARTED) return s
    }
    val selector: DeviceSelector =
        _simGlasses.value?.let { SpecificDeviceSelector(it.deviceIdentifier) }
            ?: AutoDeviceSelector(filter = { it.isDisplayCapable() })

    status("Starting session…")
    val created = Wearables.createSession(selector)
    val newSession =
        created.getOrNull()
            ?: run {
              val err = created.errorOrNull()
              status("Could not create session: ${err?.description ?: "unknown error"}")
              return null
            }
    session = newSession
    sessionJobs +=
        viewModelScope.launch {
          newSession.state.collect { state ->
            _ui.update { it.copy(sessionState = state) }
            if (state == DeviceSessionState.STOPPED) cleanup("Session stopped")
          }
        }
    sessionJobs += viewModelScope.launch { newSession.errors.collect { e -> onSessionError(e) } }
    newSession.start()

    val started =
        withTimeoutOrNull(15_000) {
          newSession.state.first { it == DeviceSessionState.STARTED }
        }
    if (started == null) {
      status("Session did not start; is Meta AI connected to the glasses?")
      return null
    }
    return newSession
  }

  private fun attachDisplay(s: DeviceSession) {
    if (display != null) return
    status("Attaching display…")
    s.addDisplay()
        .onSuccess { d ->
          display = d
          displayJob =
              viewModelScope.launch {
                d.state.collect { state ->
                  _ui.update { it.copy(displayState = state) }
                  if (state == DisplayState.STARTED) {
                    status("Showing on glasses")
                    currentScreen()
                  }
                }
              }
        }
        .onFailure { error, _ -> status("Could not attach display: ${error.description}") }
  }

  private fun detachDisplay(s: DeviceSession) {
    if (display == null) return
    displayJob?.cancel()
    displayJob = null
    display = null
    s.removeDisplay()
    _ui.update { it.copy(displayState = null) }
  }

  // ---- Camera ----

  /**
   * Takes one photo with the glasses camera and shows it in the phone app. Call only after the
   * glasses camera permission is granted (see MainActivity).
   */
  fun capture() {
    if (_ui.value.capturing) return
    _ui.update { it.copy(capturing = true, captureProgress = null) }
    viewModelScope.launch {
      try {
        val s = ensureSession() ?: return@launch
        val hadDisplay = display != null
        detachDisplay(s)

        val data = takePhoto(s) ?: takePhoto(s, retry = true)
        if (data != null) onPhoto(data)

        if (hadDisplay || data != null) {
          currentScreen = if (data != null) ::showPhotoReady else ::showChat
          attachDisplay(s)
        }
      } finally {
        _ui.update { it.copy(capturing = false, captureProgress = null) }
      }
    }
  }

  /** One capture attempt: add camera, wait for ready, capture, wait for the image, stop camera. */
  private suspend fun takePhoto(s: DeviceSession, retry: Boolean = false): PhotoCaptureData? {
    if (retry) status("Retrying camera…") else status("Opening camera…")
    val added = s.addCamera()
    val camera =
        added.getOrNull()
            ?: run {
              status("Could not open camera: ${added.errorOrNull()?.description}")
              return null
            }
    val photo = camera.photo
    try {
      // Subscribe before anything can arrive: these SharedFlows do not replay.
      val image = viewModelScope.async { photo.photoStream.first() }
      val error = viewModelScope.async { photo.errors.first() }
      val progress =
          viewModelScope.launch {
            photo.transferProgressStream.collect { p ->
              _ui.update { it.copy(captureProgress = p.fraction) }
            }
          }

      try {
        val ready =
            withTimeoutOrNull(15_000) {
              photo.state.first { it == PhotoState.STARTED }
            }
        if (ready == null) {
          status(
              if (error.isCompleted) "Camera error: ${error.await().description}"
              else "Camera did not get ready")
          return null
        }

        status("Taking photo…")
        photo.capturePhoto(PhotoResolution.LARGE, PhotoQuality.HIGH)

        // The capture fails if the transfer goes silent for 30 s; give it a little longer.
        return withTimeout(45_000) {
          kotlinx.coroutines.selects.select {
            image.onAwait { it }
            error.onAwait { e ->
              status("Capture failed: ${e.description}")
              null
            }
          }
        }
      } finally {
        image.cancel()
        error.cancel()
        progress.cancel()
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      status("Capture timed out")
      return null
    } finally {
      camera.stop()
      s.removeCamera()
    }
  }

  private suspend fun onPhoto(data: PhotoCaptureData) {
    val bytes = data.imageData
    val bitmap =
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap == null) {
      status("Got ${bytes.size / 1024} KB but could not decode it")
      return
    }
    // Keep the original bytes on disk for debugging.
    withContext(Dispatchers.IO) {
      File(getApplication<Application>().filesDir, "last_capture.img").writeBytes(bytes)
    }
    _photo.value = CapturedPhoto(bitmap, bytes.size, System.currentTimeMillis())
    _pendingPhoto.value = bitmap
    Log.i(TAG, "Photo: ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes")
    status("Photo captured (${bitmap.width}×${bitmap.height}, ${bytes.size / 1024} KB)")
  }

  // ---- Chat with Grok ----

  /** Attaches a photo (e.g. from the gallery) to the next message. */
  fun setPendingPhoto(bitmap: Bitmap?) {
    _pendingPhoto.value = bitmap
  }

  /**
   * Sends [text] (plus the pending photo, if any) and streams Grok's reply into the chat. With a
   * photo and no text, asks the default photo question.
   */
  fun sendMessage(text: String) {
    if (_ui.value.thinking) return
    val image = _pendingPhoto.value
    val prompt = text.trim().ifEmpty { if (image != null) DEFAULT_PHOTO_PROMPT else return }
    _pendingPhoto.value = null

    val user = ChatMessage(nextId++, Role.USER, prompt, image)
    val replyId = nextId++
    _chat.update { it + user + ChatMessage(replyId, Role.ASSISTANT, "", streaming = true) }
    _ui.update { it.copy(thinking = true) }
    currentScreen = ::showChat
    replyPage = 0
    showOnGlasses()

    replyJob =
        viewModelScope.launch {
          try {
            val request = withContext(Dispatchers.Default) { buildRequest() }
            val started = System.currentTimeMillis()
            grok.stream(request) { delta ->
              editReply(replyId) { it.copy(text = it.text + delta) }
              refreshGlasses()
            }
            Log.i(TAG, "Grok replied in ${System.currentTimeMillis() - started} ms")
            editReply(replyId) { it.copy(streaming = false) }
          } catch (e: CancellationException) {
            editReply(replyId) {
              it.copy(text = it.text.ifEmpty { "(stopped)" }, streaming = false)
            }
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Grok failed", e)
            editReply(replyId) {
              it.copy(text = e.message ?: "Grok request failed", streaming = false, failed = true)
            }
          } finally {
            _ui.update { it.copy(thinking = false) }
            refreshGlasses(now = true)
          }
        }
  }

  fun stopReply() {
    replyJob?.cancel()
  }

  fun newChat() {
    replyJob?.cancel()
    _chat.value = emptyList()
    _pendingPhoto.value = null
    currentScreen = ::showChat
    refreshGlasses(now = true)
  }

  private fun editReply(id: Long, change: (ChatMessage) -> ChatMessage) {
    _chat.update { list -> list.map { if (it.id == id) change(it) else it } }
  }

  /**
   * System prompt + history. Only the newest photo goes as an image; earlier ones become the text
   * "[photo]" so each request stays small. Failed replies and the empty in-progress reply are left
   * out.
   */
  private fun buildRequest(): JSONArray {
    val history = _chat.value.filter { !it.failed && !(it.role == Role.ASSISTANT && it.streaming) }
    val newestPhotoId = history.lastOrNull { it.image != null }?.id
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
    for (m in history) {
      if (m.role == Role.ASSISTANT) {
        if (m.text.isNotBlank()) {
          messages.put(JSONObject().put("role", "assistant").put("content", m.text))
        }
        continue
      }
      val content: Any =
          when {
            m.image != null && m.id == newestPhotoId ->
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject()
                                    .put("url", "data:image/jpeg;base64,${jpegBase64(m.image)}")
                                    .put("detail", "high")))
                    .put(JSONObject().put("type", "text").put("text", m.text))
            m.image != null -> "[photo] ${m.text}"
            else -> m.text
          }
      messages.put(JSONObject().put("role", "user").put("content", content))
    }
    return messages
  }

  /** JPEG, long side at most 1568 px, base64 without line breaks. */
  private fun jpegBase64(bitmap: Bitmap): String {
    val longSide = maxOf(bitmap.width, bitmap.height)
    val scaled =
        if (longSide <= 1568) bitmap
        else {
          val f = 1568f / longSide
          Bitmap.createScaledBitmap(
              bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
        }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    Log.i(TAG, "Photo for Grok: ${scaled.width}x${scaled.height}, ${out.size() / 1024} KB")
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  // ---- What appears on the glasses. Every send replaces the whole screen. ----

  /**
   * Shows the chat on the glasses when they are usable (registered, or the simulator is on),
   * starting a session if there isn't one.
   */
  private fun showOnGlasses() {
    val usable =
        _ui.value.useSimulator || _ui.value.registrationState == RegistrationState.REGISTERED
    if (!usable) return
    if (display != null) refreshGlasses(now = true) else start()
  }

  /**
   * Redraws the current screen. Streaming deltas call this many times a second, so sends are
   * limited to one per second; [now] skips the wait (for button presses and finished replies).
   */
  private fun refreshGlasses(now: Boolean = false) {
    if (display == null) return
    if (now) refreshJob?.cancel() else if (refreshJob?.isActive == true) return
    val wait = if (now) 0L else (lastScreenAt + 1_000 - System.currentTimeMillis()).coerceAtLeast(0)
    refreshJob =
        viewModelScope.launch {
          delay(wait)
          lastScreenAt = System.currentTimeMillis()
          currentScreen()
        }
  }

  /** The latest exchange: your last message on one line, then a page of Grok's reply. */
  private fun showChat() {
    val chat = _chat.value
    val reply = chat.lastOrNull { it.role == Role.ASSISTANT }
    val question = chat.lastOrNull { it.role == Role.USER }
    val streaming = reply?.streaming == true
    val pages = if (reply == null || reply.text.isEmpty()) emptyList() else pages(reply.text)
    if (replyPage >= pages.size) replyPage = 0
    val canPhoto = !_ui.value.useSimulator && !_ui.value.capturing

    send {
      flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 20, gap = 6, background = FlexBoxBackground.CARD) {
          when {
            question == null -> {
              text("Grok", style = TextStyle.HEADING)
              text(
                  if (canPhoto) "Tap Photo to ask about what you see, or type on your phone."
                  else "Type a message on your phone.",
                  style = TextStyle.BODY,
              )
            }
            else -> {
              text(
                  oneLine((if (question.image != null) "Photo: " else "You: ") + question.text),
                  style = TextStyle.META,
                  color = TextColor.SECONDARY,
              )
              text(
                  pages.getOrNull(replyPage) ?: "Thinking…",
                  style = TextStyle.BODY,
                  color = if (reply?.failed == true) TextColor.SECONDARY else TextColor.PRIMARY,
              )
              val footer =
                  when {
                    streaming && pages.isEmpty() -> null
                    streaming -> "Grok is writing…"
                    reply?.failed == true -> "Couldn't reach Grok"
                    pages.size > 1 -> "Page ${replyPage + 1} of ${pages.size}"
                    else -> null
                  }
              footer?.let { text(it, style = TextStyle.META, color = TextColor.SECONDARY) }
            }
          }
        }
        flexBox(
            direction = Direction.ROW,
            alignment = Alignment.CENTER,
            crossAlignment = Alignment.CENTER,
        ) {
          buttonGroup {
            if (streaming) {
              button("Stop", style = ButtonStyle.SECONDARY, onClick = { stopReply() })
            } else {
              if (canPhoto) {
                button("Photo", style = ButtonStyle.PRIMARY, onClick = { capture() })
              }
              if (pages.size > 1) {
                button(
                    if (replyPage == pages.size - 1) "From start" else "More",
                    style = if (canPhoto) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
                    onClick = {
                      replyPage = (replyPage + 1) % pages.size
                      refreshGlasses(now = true)
                    },
                )
              }
            }
          }
        }
      }
    }
  }

  /** After a glasses photo: send it with the default question, or drop it. */
  private fun showPhotoReady() {
    val p = _pendingPhoto.value ?: return showChat()
    send {
      flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 20, gap = 6, background = FlexBoxBackground.CARD) {
          text("Photo ready", style = TextStyle.HEADING)
          text("Send it to Grok, or add a question on your phone.", style = TextStyle.BODY)
          text("${p.width}×${p.height}", style = TextStyle.META, color = TextColor.SECONDARY)
        }
        flexBox(
            direction = Direction.ROW,
            alignment = Alignment.CENTER,
            crossAlignment = Alignment.CENTER,
        ) {
          buttonGroup {
            button(
                "Send",
                style = ButtonStyle.PRIMARY,
                iconName = IconName.CHECKMARK,
                onClick = { sendMessage("") },
            )
            button(
                "Discard",
                style = ButtonStyle.SECONDARY,
                onClick = {
                  _pendingPhoto.value = null
                  currentScreen = ::showChat
                  refreshGlasses(now = true)
                },
            )
          }
        }
      }
    }
  }

  private fun oneLine(text: String, max: Int = 34): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
  }

  /**
   * Splits a reply into pages of whole words. The real glasses draw text larger than the
   * simulator, and a taller card pushes the buttons off the bottom of the display, so pages stay
   * short (about 4 lines).
   */
  private fun pages(text: String, max: Int = 130): List<String> {
    val out = mutableListOf<String>()
    val page = StringBuilder()
    for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
      if (page.isNotEmpty() && page.length + 1 + word.length > max) {
        out += page.toString()
        page.clear()
      }
      if (page.isNotEmpty()) page.append(' ')
      page.append(word)
    }
    if (page.isNotEmpty()) out += page.toString()
    return out
  }

  private fun send(content: ContentScope.() -> Unit) {
    val d = display ?: return status("No display attached")
    viewModelScope.launch(Dispatchers.IO) {
      d.sendContent(content)
          .onSuccess { Log.i(TAG, "Sent screen to glasses") }
          .onFailure { error, _ -> status("Send failed: ${error.description}") }
    }
  }

  private fun onSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    session?.stop()
    cleanup(
        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
          "Update the Device Access Toolkit app on the glasses (Meta AI app > App connections)"
        } else {
          "Session error: ${error.description}"
        })
  }

  private fun cleanup(message: String) {
    displayJob?.cancel()
    displayJob = null
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    display = null
    session = null
    _ui.update { it.copy(sessionState = null, displayState = null) }
    status(message)
  }

  private fun status(message: String) {
    Log.i(TAG, message)
    _ui.update { it.copy(status = message) }
  }

  override fun onCleared() {
    stop()
    if (_ui.value.useSimulator) MockDeviceKit.getInstance(getApplication()).disable()
    super.onCleared()
  }
}
