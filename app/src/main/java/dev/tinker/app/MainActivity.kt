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
        // como o GameGuardian: abrir o app já abre o menu flutuante e sai da frente (a permissão é pedida só na primeira vez)
        if (savedInstanceState == null && Session.autoOverlay) {
            if (Settings.canDrawOverlays(this)) openOverlay()
            else if (engine.firstOverlayAsk()) { waitingPermission = true; openOverlay() }
        }
        setContent {
            TinkerTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { TinkerPanel(engine, overlay = false, onOpenOverlay = ::openOverlay, onExit = {
                    engine.shutdown()
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                    finishAndRemoveTask()
                }) }
            }
        }
    }

    private var waitingPermission = false

    // voltou da tela de permissão já com ela dada: abre o menu flutuante sozinho
    override fun onPause() {
        super.onPause()
        Session.appVisible = false
    }

    override fun onResume() {
        super.onResume()
        Session.appVisible = true
        if (waitingPermission && Settings.canDrawOverlays(this)) {
            waitingPermission = false
            openOverlay()
        }
    }

    private fun openOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Diag.add("Pedindo permissão de sobreposição")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            waitingPermission = true
            return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }
}

/** Depois de "Atualizar e testar sozinho": o Android avisa que o Tinker foi atualizado e ele reabre para rodar o autoteste. */
class UpdatedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = context.getSharedPreferences("tinker", android.content.Context.MODE_PRIVATE).getString("testarApos", "").orEmpty()
        if (pending.isNotEmpty()) runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
