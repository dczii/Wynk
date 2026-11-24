package com.personal.wynk

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.types.DisplayState

class MainActivity : ComponentActivity() {
  private val vm: GlassesViewModel by viewModels()

  private val permissions =
      registerForActivityResult(RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
          vm.initialize()
        } else {
          Toast.makeText(this, "Bluetooth permission is required", Toast.LENGTH_LONG).show()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) { Screen(vm, this) }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    permissions.launch(arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET))
  }
}

@Composable
private fun Screen(vm: GlassesViewModel, activity: ComponentActivity) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val sim by vm.simGlasses.collectAsStateWithLifecycle()
  val registered = ui.registrationState == RegistrationState.REGISTERED
  val running = ui.sessionState != null

  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Wynk", style = MaterialTheme.typography.headlineMedium)

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Registration: ${ui.registrationState}")
        Text("Devices: ${ui.deviceCount}")
        Text("Session: ${ui.sessionState ?: "none"}")
        Text("Display: ${ui.displayState ?: "none"}")
        Text("Taps from glasses: ${ui.tapCount}")
        Text(ui.status, color = MaterialTheme.colorScheme.primary)
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Use simulated glasses")
        Text(
            "Preview the 600×600 display here, no hardware needed",
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Switch(checked = ui.useSimulator, onCheckedChange = { vm.setUseSimulator(it) })
    }

    if (!ui.useSimulator) {
      if (registered) {
        OutlinedButton(onClick = { vm.unregister(activity) }, modifier = Modifier.fillMaxWidth()) {
          Text("Disconnect app from glasses")
        }
      } else {
        Button(onClick = { vm.register(activity) }, modifier = Modifier.fillMaxWidth()) {
          Text("Connect to glasses (opens Meta AI app)")
        }
      }
    }

    Button(
        onClick = { vm.start() },
        enabled = ui.useSimulator || registered,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (running) "Re-send hello screen" else "Show on glasses")
    }
    OutlinedButton(onClick = { vm.stop() }, enabled = running, modifier = Modifier.fillMaxWidth()) {
      Text("Stop session")
    }

    val kit = sim?.services?.display
    if (kit != null && ui.displayState == DisplayState.STARTED) {
      Spacer(Modifier)
      Text("Simulated display", style = MaterialTheme.typography.titleMedium)
      key(kit) {
        AndroidView(
            factory = { ctx -> kit.createPreviewView(ctx) },
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
        )
      }
    }
  }
}
