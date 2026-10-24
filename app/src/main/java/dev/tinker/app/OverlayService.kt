package dev.tinker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Botão flutuante sobre o jogo; tocar abre o painel transparente do Tinker. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val owner = OverlayOwner()
    private var bubble: ComposeView? = null
    private var panel: ComposeView? = null
    private var panelParams: LayoutParams? = null

    private val bubbleParams = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 320 }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wm = getSystemService(WindowManager::class.java)
        owner.start()
        showBubble()
        Diag.add("Menu flutuante aberto")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubble?.let(wm::removeView)
        panel?.let(wm::removeView)
        owner.stop()
        Diag.add("Menu flutuante fechado")
        super.onDestroy()
    }

    private fun compose(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { TinkerTheme(content) }
    }

    private fun showBubble() {
        val view = compose {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            bubbleParams.x += drag.x.toInt()
                            bubbleParams.y += drag.y.toInt()
                            bubble?.let { wm.updateViewLayout(it, bubbleParams) }
                        }
                    }
                    .clickable { openPanel() },
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Abrir Tinker", modifier = Modifier.size(56.dp)) }
        }
        bubble = view
        wm.addView(view, bubbleParams)
    }

    private fun openPanel() {
        if (panel != null) return
        bubble?.let { wm.removeView(it) }
        bubble = null
        // focável para o teclado funcionar; NOT_TOUCH_MODAL deixa o toque fora do painel ir para o jogo
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT, panelHeight(),
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = compose {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (Session.peek) 0.06f else Session.alpha }.clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))) {
                TinkerPanel(Engine.get(this@OverlayService), overlay = true, onMinimize = ::closePanel, onResize = ::resizePanel, onExit = {
                    Engine.get(this@OverlayService).shutdown()
                    stopSelf()
                })
            }
        }
        panel = view
        panelParams = params
        wm.addView(view, params)
    }

    private fun panelHeight() = (resources.displayMetrics.heightPixels * PANEL_SIZES[Session.panelSize].first).toInt()

    private fun resizePanel() {
        Session.panelSize = (Session.panelSize + 1) % PANEL_SIZES.size
        val view = panel ?: return
        val params = panelParams ?: return
        params.height = panelHeight()
        wm.updateViewLayout(view, params)
    }

    private fun closePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
        showBubble()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Menu flutuante", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Tinker ativo")
            .setContentText("Toque no botão flutuante para abrir o painel")
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher_foreground), "Fechar menu", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    private companion object {
        const val CHANNEL = "overlay"
        const val ACTION_STOP = "dev.tinker.app.STOP"
    }
}

/** Compose precisa de um ciclo de vida; um Service não tem, então criamos um que fica sempre ativo. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = controller.savedStateRegistry

    fun start() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
