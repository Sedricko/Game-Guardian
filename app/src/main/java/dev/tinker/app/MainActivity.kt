package dev.tinker.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = Engine.get(this)
        setContent {
            TinkerTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { TinkerPanel(engine, overlay = false, onOpenOverlay = ::openOverlay) }
            }
        }
    }

    private fun openOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Diag.add("Pedindo permissão de sobreposição")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }
}
