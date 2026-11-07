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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.spektralab.android.ui.CameraScreen
import com.spektralab.android.ui.EditorScreen
import com.spektralab.android.ui.SpektraTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // "Open with" / "Share to" from the gallery
        if (savedInstanceState == null) incomingImage()?.let { vm.loadPhoto(it) }
        setContent {
            SpektraTheme {
                var camera by rememberSaveable { mutableStateOf(false) }
                if (camera) {
                    CameraScreen(
                        onCaptured = { uri -> vm.loadPhoto(uri); camera = false },
                        onBack = { camera = false },
                        liveParams = vm::liveParams,
                        filmName = vm.filmName(),
                    )
                } else {
                    EditorScreen(vm, onCamera = { camera = true })
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
