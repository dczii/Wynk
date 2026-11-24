package com.personal.wynk

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

private const val TAG = "Wynk"

data class UiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val deviceCount: Int = 0,
    val sessionState: DeviceSessionState? = null,
    val displayState: DisplayState? = null,
    val useSimulator: Boolean = false,
    val tapCount: Int = 0,
    val capturing: Boolean = false,
    val captureProgress: Double? = null,
    val status: String = "Idle",
)

/** The last photo taken with the glasses camera. */
data class CapturedPhoto(val bitmap: Bitmap, val bytes: Int, val millis: Long)

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

  private var initialized = false
  private var session: DeviceSession? = null
  private var display: Display? = null
  private var displayJob: Job? = null
  private val sessionJobs = mutableListOf<Job>()

  /** What to draw whenever the display (re)starts. */
  private var currentScreen: () -> Unit = ::showHello

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
                  if (state == DisplayState.STARTED) currentScreen()
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
          currentScreen = if (data != null) ::showCaptured else currentScreen
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
    // Keep the original bytes for the next phase (sending to Claude).
    withContext(Dispatchers.IO) {
      File(getApplication<Application>().filesDir, "last_capture.img").writeBytes(bytes)
    }
    _photo.value = CapturedPhoto(bitmap, bytes.size, System.currentTimeMillis())
    Log.i(TAG, "Photo: ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes")
    status("Photo captured (${bitmap.width}×${bitmap.height}, ${bytes.size / 1024} KB)")
  }

  // ---- What appears on the glasses. Every send replaces the whole screen. ----

  private fun showHello() {
    val count = _ui.value.tapCount
    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    send {
      flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD) {
          text("Hello from Wynk", style = TextStyle.HEADING)
          text("It's $time. Taps so far: $count", style = TextStyle.BODY)
          text(
              "Swipe to pick a button, tap to press it",
              style = TextStyle.META,
              color = TextColor.SECONDARY,
          )
        }
        flexBox(
            direction = Direction.ROW,
            gap = 8,
            alignment = Alignment.CENTER,
            crossAlignment = Alignment.CENTER,
        ) {
          buttonGroup {
            button(
                "Tap me",
                style = ButtonStyle.PRIMARY,
                iconName = IconName.CHECKMARK,
                onClick = {
                  _ui.update { it.copy(tapCount = it.tapCount + 1) }
                  showHello()
                },
            )
            button(
                "Refresh",
                style = ButtonStyle.SECONDARY,
                onClick = { showHello() },
            )
          }
        }
      }
    }
  }

  private fun showCaptured() {
    val p = _photo.value
    send {
      flexBox(direction = Direction.COLUMN, gap = 12) {
        flexBox(padding = 24, background = FlexBoxBackground.CARD) {
          text("Photo captured", style = TextStyle.HEADING)
          text(
              if (p != null) "${p.bitmap.width}×${p.bitmap.height}, ${p.bytes / 1024} KB"
              else "No photo",
              style = TextStyle.BODY,
          )
          text(
              "Next phase: Claude reads it and sends hints here",
              style = TextStyle.META,
              color = TextColor.SECONDARY,
          )
        }
        flexBox(
            direction = Direction.ROW,
            alignment = Alignment.CENTER,
            crossAlignment = Alignment.CENTER,
        ) {
          buttonGroup {
            button(
                "New photo",
                style = ButtonStyle.PRIMARY,
                iconName = IconName.CHECKMARK,
                onClick = { capture() },
            )
          }
        }
      }
    }
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
