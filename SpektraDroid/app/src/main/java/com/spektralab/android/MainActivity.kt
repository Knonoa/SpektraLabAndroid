package com.spektralab.android

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spektralab.android.camera.CameraViewModel
import com.spektralab.android.tether.TetherViewModel
import com.spektralab.android.ui.*

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingImage()?.let { vm.loadPhoto(it) }
        setContent {
            SpektraTheme {
                var mode by rememberSaveable { mutableIntStateOf(0) }
                var pendingMode by rememberSaveable { mutableStateOf<Int?>(null) }
                var pendingPhoto by remember { mutableStateOf<MediaItem?>(null) }
                val editState by vm.state.collectAsState()
                val exporting by vm.exporting.collectAsState()
                val loading by vm.loadingPhoto.collectAsState()
                val capture: CameraViewModel = viewModel()
                val tether: TetherViewModel = viewModel()
                val cameraUi by capture.ui.collectAsState()
                val connected by tether.connected.collectAsState()
                val busy by tether.busy.collectAsState()
                val library = remember { MediaLibrary.get(this@MainActivity) }
                SideEffect { vm.rememberPhotoEdits = mode == 0 }
                fun switch(target: Int, photo: MediaItem? = null) {
                    if (target == mode && photo == null) return
                    if (exporting || loading || cameraUi.capturing) return
                    if (mode == 2 && (connected || busy)) {
                        pendingPhoto = photo; pendingMode = target; tether.stop()
                    } else {
                        vm.rememberPhotoEdits = target == 0
                        photo?.let { vm.loadPhoto(Uri.parse(it.parentUri ?: it.uri), it.recipe) }
                        mode = target
                    }
                }
                LaunchedEffect(connected, busy, pendingMode) {
                    val target = pendingMode
                    if (target != null && !connected && !busy) {
                        vm.rememberPhotoEdits = target == 0
                        pendingPhoto?.let { vm.loadPhoto(Uri.parse(it.parentUri ?: it.uri), it.recipe) }
                        pendingPhoto = null; pendingMode = null; mode = target
                    }
                }
                Surface(Modifier.fillMaxSize(), color = Palette.Background, contentColor = Palette.Text) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (mode) {
                            1 -> CameraScreen(editor = vm, onEdit = { switch(0, it) }, onBack = { switch(0) },
                                liveParams = vm::liveParams, filmName = vm.filmName())
                            2 -> TetherScreen(vm, editState, vm.filmName(), onBack = { switch(0) }, onPhoto = { switch(0, it) })
                            else -> Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) {
                                    EditorScreen(vm, onCamera = { switch(1) }, onTether = { switch(2) })
                                }
                                MediaStrip(library, onEdit = { switch(0, it) }, enabled = !exporting && !loading)
                            }
                        }
                    }
                    NavigationBar(containerColor = Palette.Panel) {
                        listOf("工作台" to Icons.Outlined.Tune, "相机" to Icons.Outlined.PhotoCamera,
                            "监视器" to Icons.Outlined.Videocam).forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(selected = mode == index, onClick = { switch(index) },
                                enabled = !exporting && !loading && !cameraUi.capturing && pendingMode == null,
                                icon = { Icon(icon, null) }, label = { Text(label) })
                        }
                    }
                }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingImage(): Uri? = when (intent?.action) {
        android.content.Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        android.content.Intent.ACTION_VIEW, android.content.Intent.ACTION_EDIT -> intent.data
        else -> null
    }
}
