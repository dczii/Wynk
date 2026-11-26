package com.personal.wynk

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

  // The glasses camera permission is granted by you in the Meta AI app, not by Android.
  private val cameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrNull() == PermissionStatus.Granted) {
          vm.capture()
        } else {
          Toast.makeText(this, "Glasses camera permission was not granted", Toast.LENGTH_LONG)
              .show()
        }
      }

  /** Checks the glasses camera permission, asks for it if needed, then captures. */
  fun captureWithPermission() {
    lifecycleScope.launch {
      val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
      if (status == PermissionStatus.Granted) vm.capture()
      else cameraPermission.launch(Permission.CAMERA)
    }
  }

  // Android photo picker; no storage permission needed.
  private val gallery =
      registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
          val bitmap =
              withContext(Dispatchers.IO) {
                runCatching {
                      ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) {
                          decoder,
                          info,
                          _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longSide = maxOf(info.size.width, info.size.height)
                        if (longSide > 2048) {
                          val f = 2048f / longSide
                          decoder.setTargetSize(
                              (info.size.width * f).toInt(), (info.size.height * f).toInt())
                        }
                      }
                    }
                    .getOrNull()
              }
          if (bitmap != null) vm.setPendingPhoto(bitmap)
          else Toast.makeText(this@MainActivity, "Could not open that image", Toast.LENGTH_LONG).show()
        }
      }

  fun pickFromGallery() =
      gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) { Screen(vm, this, ::captureWithPermission, ::pickFromGallery) }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    permissions.launch(arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET))
  }
}

@Composable
private fun Screen(
    vm: GlassesViewModel,
    activity: ComponentActivity,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val chat by vm.chat.collectAsStateWithLifecycle()
  val pending by vm.pendingPhoto.collectAsStateWithLifecycle()
  val registered = ui.registrationState == RegistrationState.REGISTERED
  var showGlasses by rememberSaveable { mutableStateOf(!registered) }
  var draft by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Follow the conversation as messages arrive and replies stream in.
  LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) {
    val last = listState.layoutInfo.totalItemsCount - 1
    if (chat.isNotEmpty() && last >= 0) listState.scrollToItem(last)
  }

  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Wynk", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = { showGlasses = !showGlasses }) {
        Text(if (showGlasses) "Hide glasses" else "Glasses")
      }
      TextButton(onClick = { vm.newChat() }, enabled = chat.isNotEmpty()) { Text("New chat") }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (showGlasses) item(key = "glasses") { GlassesPanel(vm, activity) }
      if (chat.isEmpty()) {
        item(key = "empty") {
          Text(
              "Ask Grok anything. Type below, take a photo with the glasses, or pick one from " +
                  "the gallery.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 24.dp),
          )
        }
      }
      items(chat, key = { it.id }) { Bubble(it) }
    }

    HorizontalDivider()
    Composer(
        ui = ui,
        registered = registered,
        pending = pending,
        draft = draft,
        onDraft = { draft = it },
        onSend = {
          vm.sendMessage(draft)
          draft = ""
        },
        onStop = { vm.stopReply() },
        onCapture = onCapture,
        onGallery = onGallery,
        onRemovePhoto = { vm.setPendingPhoto(null) },
    )
  }
}

@Composable
private fun Bubble(m: ChatMessage) {
  val mine = m.role == Role.USER
  Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
    Column(
        Modifier.widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                  m.failed -> MaterialTheme.colorScheme.errorContainer
                  mine -> MaterialTheme.colorScheme.primaryContainer
                  else -> MaterialTheme.colorScheme.surfaceVariant
                })
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      m.image?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Photo sent to Grok",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
        )
      }
      Text(
          if (m.streaming && m.text.isEmpty()) "…" else m.text,
          style = MaterialTheme.typography.bodyLarge,
          color =
              when {
                m.failed -> MaterialTheme.colorScheme.onErrorContainer
                mine -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
  }
}

@Composable
private fun Composer(
    ui: UiState,
    registered: Boolean,
    pending: Bitmap?,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (ui.capturing) {
      Text(ui.status, style = MaterialTheme.typography.bodySmall)
      val p = ui.captureProgress
      if (p != null) LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth())
      else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    pending?.let { photo ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = "Photo to send",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text("Photo attached", modifier = Modifier.weight(1f))
        TextButton(onClick = onRemovePhoto) { Text("Remove") }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
          onClick = onCapture,
          enabled = !ui.useSimulator && registered && !ui.capturing,
          modifier = Modifier.weight(1f),
      ) {
        Text(if (ui.capturing) "Capturing…" else "Glasses photo")
      }
      OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) { Text("Gallery") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
          value = draft,
          onValueChange = onDraft,
          placeholder = { Text(if (pending != null) "Ask about the photo" else "Message Grok") },
          maxLines = 4,
          modifier = Modifier.weight(1f),
      )
      if (ui.thinking) {
        OutlinedButton(onClick = onStop) { Text("Stop") }
      } else {
        Button(onClick = onSend, enabled = draft.isNotBlank() || pending != null) { Text("Send") }
      }
    }
  }
}

/** Connection, session and simulator controls. */
@Composable
private fun GlassesPanel(vm: GlassesViewModel, activity: ComponentActivity) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val sim by vm.simGlasses.collectAsStateWithLifecycle()
  val registered = ui.registrationState == RegistrationState.REGISTERED
  val running = ui.sessionState != null

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Registration: ${ui.registrationState}")
        Text("Devices: ${ui.deviceCount}")
        Text("Session: ${ui.sessionState ?: "none"}")
        Text("Display: ${ui.displayState ?: "none"}")
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
      Text(if (running) "Refresh glasses" else "Show chat on glasses")
    }
    OutlinedButton(onClick = { vm.stop() }, enabled = running, modifier = Modifier.fillMaxWidth()) {
      Text("Stop session")
    }

    val kit = sim?.services?.display
    if (kit != null && ui.displayState == DisplayState.STARTED) {
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
