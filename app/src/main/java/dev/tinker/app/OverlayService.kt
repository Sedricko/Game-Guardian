package dev.tinker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var bar: ComposeView? = null
    // começa sem foco: o jogo continua recebendo as teclas; só pega o teclado enquanto o campo é usado
    private val barParams = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 120 }

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
        // o jogo fechou sozinho: abre o painel com a pergunta "Reabrir o jogo?", como o GameGuardian
        // trocou o lado do painel em Ajustes: muda na hora
        watch.launch { snapshotFlow { Session.panelLeft }.collect { relayout() } }
        watch.launch {
            snapshotFlow { Session.gameGone }.collect { gone ->
                if (gone != null && panel == null && !Session.gameGoneByUser) {
                    closeBar()
                    openPanel()
                }
            }
        }
    }

    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubble?.let(wm::removeView)
        panel?.let(wm::removeView)
        bar?.let(wm::removeView)
        watch.cancel()
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
                    // toque: o modo de sempre; segurar: o outro (painel ↔ barra rápida), sem precisar passar pelo ▢
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (Session.useBar) showBar() else openPanel() },
                            onLongPress = { if (Session.useBar) { Session.useBar = false; openPanel() } else showBar() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Abrir Tinker", modifier = Modifier.size(56.dp)) }
        }
        bubble = view
        wm.addView(view, bubbleParams)
    }

    private fun showBar() {
        if (bar != null) return
        bubble?.let { wm.removeView(it) }
        bubble = null
        Session.useBar = true
        val view = compose {
            QuickBar(
                Engine.get(this@OverlayService),
                onDrag = { dx, dy ->
                    barParams.x += dx.toInt()
                    barParams.y += dy.toInt()
                    bar?.let { wm.updateViewLayout(it, barParams) }
                },
                onKeyboard = ::barFocus,
                onOpenPanel = { closeBar(); Session.useBar = false; openPanel() },
                onBubble = { closeBar(); showBubble() },
            )
        }
        bar = view
        barParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE
        wm.addView(view, barParams)
    }

    private fun barFocus(on: Boolean) {
        val view = bar ?: return
        val flags = if (on) LayoutParams.FLAG_NOT_TOUCH_MODAL else LayoutParams.FLAG_NOT_FOCUSABLE
        if (barParams.flags == flags) return
        barParams.flags = flags
        wm.updateViewLayout(view, barParams)
    }

    private fun closeBar() {
        bar?.let { wm.removeView(it) }
        bar = null
    }

    private fun openPanel() {
        if (panel != null) return
        bubble?.let { wm.removeView(it) }
        bubble = null
        // focável para o teclado funcionar; NOT_TOUCH_MODAL deixa o toque fora do painel ir para o jogo
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        place(params)
        val view = compose {
            // cantos arredondados do lado que fica sobre o jogo
            val side = Session.panelWide
            val shape = when {
                !side -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                Session.panelLeft -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                else -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (Session.peek) 0.06f else Session.alpha }.clip(shape)) {
                TinkerPanel(Engine.get(this@OverlayService), overlay = true, onMinimize = ::closePanel, onResize = ::resizePanel, onExit = {
                    Engine.get(this@OverlayService).shutdown()
                    stopSelf()
                }, onQuickBar = {
                    panel?.let { wm.removeView(it) }
                    panel = null
                    showBar()
                })
            }
        }
        panel = view
        panelParams = params
        wm.addView(view, params)
    }

    /** Tamanho da tela agora (gira com o jogo, muda em tela dividida). */
    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /**
     * Onde o painel fica: em pé, em cima, com a altura do tamanho escolhido; deitado, de lado (direita ou esquerda),
     * com a largura do tamanho escolhido e a altura toda: o jogo continua à vista do outro lado.
     */
    private fun place(p: LayoutParams) {
        val (w, h) = screen()
        val f = PANEL_SIZES[Session.panelSize].first
        Session.panelWide = w > h && f < 1f
        if (w > h) {
            p.width = (w * f).toInt(); p.height = LayoutParams.MATCH_PARENT
            p.gravity = Gravity.TOP or if (Session.panelLeft) Gravity.START else Gravity.END
        } else {
            p.width = LayoutParams.MATCH_PARENT; p.height = (h * f).toInt()
            p.gravity = Gravity.TOP
        }
    }

    private fun relayout() {
        val view = panel ?: return
        val params = panelParams ?: return
        place(params)
        wm.updateViewLayout(view, params)
    }

    private fun resizePanel() {
        Session.panelSize = (Session.panelSize + 1) % PANEL_SIZES.size
        Engine.get(this).saveSettings()
        relayout()
    }

    // o jogo girou (ou a tela foi dividida): o painel se refaz e a bolinha e a barra voltam para dentro da tela
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
        val (w, h) = screen()
        listOf(bubble to bubbleParams, bar to barParams).forEach { (v, p) ->
            v ?: return@forEach
            p.x = p.x.coerceIn(0, (w - 160).coerceAtLeast(0)); p.y = p.y.coerceIn(0, (h - 160).coerceAtLeast(0))
            wm.updateViewLayout(v, p)
        }
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
