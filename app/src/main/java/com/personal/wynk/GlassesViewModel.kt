package com.personal.wynk

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "Wynk"

data class UiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val deviceCount: Int = 0,
    val sessionState: DeviceSessionState? = null,
    val displayState: DisplayState? = null,
    val useSimulator: Boolean = false,
    val tapCount: Int = 0,
    val status: String = "Idle",
)

/**
 * Minimal Display Access flow, following Meta's DisplayAccess sample:
 * register -> create session -> session STARTED -> addDisplay -> display STARTED -> sendContent.
 */
class GlassesViewModel(application: Application) : AndroidViewModel(application) {
  private val _ui = MutableStateFlow(UiState())
  val ui: StateFlow<UiState> = _ui.asStateFlow()

  private val _simGlasses = MutableStateFlow<MockGlasses?>(null)
  /** Simulated Meta Ray-Ban Display glasses, rendered in-app when the simulator is on. */
  val simGlasses: StateFlow<MockGlasses?> = _simGlasses.asStateFlow()

  private var initialized = false
  private var session: DeviceSession? = null
  private var display: Display? = null
  private val jobs = mutableListOf<Job>()

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

  /** Starts a session and shows the hello screen on the glasses. */
  fun start() {
    if (session != null) {
      if (display != null) showHello()
      return
    }
    val selector: DeviceSelector =
        _simGlasses.value?.let { SpecificDeviceSelector(it.deviceIdentifier) }
            ?: AutoDeviceSelector(filter = { it.isDisplayCapable() })

    status("Starting session…")
    Wearables.createSession(selector)
        .onSuccess { newSession ->
          session = newSession
          jobs +=
              viewModelScope.launch {
                newSession.state.collect { state ->
                  _ui.update { it.copy(sessionState = state) }
                  when (state) {
                    DeviceSessionState.STARTED -> attachDisplay(newSession)
                    DeviceSessionState.STOPPED -> cleanup("Session stopped")
                    else -> {}
                  }
                }
              }
          jobs +=
              viewModelScope.launch { newSession.errors.collect { e -> onSessionError(e) } }
          newSession.start()
        }
        .onFailure { error, _ -> status("Could not create session: ${error.description}") }
  }

  fun stop() {
    session?.let {
      it.removeDisplay()
      it.stop()
    }
    cleanup("Stopped")
  }

  private fun attachDisplay(s: DeviceSession) {
    if (display != null) return
    status("Attaching display…")
    s.addDisplay()
        .onSuccess { d ->
          display = d
          jobs +=
              viewModelScope.launch {
                d.state.collect { state ->
                  _ui.update { it.copy(displayState = state) }
                  if (state == DisplayState.STARTED) showHello()
                }
              }
        }
        .onFailure { error, _ -> status("Could not attach display: ${error.description}") }
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

  private fun send(content: com.meta.wearable.dat.display.views.ContentScope.() -> Unit) {
    val d = display ?: return status("No display attached")
    viewModelScope.launch(Dispatchers.IO) {
      d.sendContent(content)
          .onSuccess { status("Showing on glasses") }
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
    jobs.forEach { it.cancel() }
    jobs.clear()
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
