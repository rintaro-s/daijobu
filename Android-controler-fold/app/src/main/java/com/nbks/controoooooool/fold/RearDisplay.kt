package com.nbks.controoooooool.fold

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter

/**
 * 広い画面を開いたまま、背面 (カバー) ディスプレイに画像を出す。
 * Jetpack WindowManager の WindowAreaController (presentContentOnWindowArea) を使う。
 *
 * 画面の状態は [status] / [active] / [available] を Compose から読む。
 * [attach] で状態の監視を始める (Activity の STARTED 中だけ有効にすること)。
 */
class RearDisplay(private val activity: ComponentActivity) : WindowAreaPresentationSessionCallback {
    private val controller = WindowAreaController.getOrCreate()
    private var info: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var imageView: ImageView? = null
    private var bitmap: Bitmap? = null

    var status by mutableStateOf("確認中…")
        private set
    var active by mutableStateOf(false)
        private set
    var available by mutableStateOf(false)
        private set

    /** windowAreaInfos を collect する (呼び出し側の lifecycleScope で動かす) */
    suspend fun observe() {
        controller.windowAreaInfos.collect { infos ->
            info = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
            val st = info?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
            available = st == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
            status = when {
                info == null -> "この端末では背面ディスプレイが見つかりません"
                st == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> "利用できます"
                st == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> "表示中"
                st == WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE ->
                    "今は使えません (画面を開いた状態でのみ利用できます)"
                else -> "非対応"
            }
        }
    }

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        imageView?.setImageBitmap(b)
    }

    fun toggle() {
        val s = session
        if (s != null) {
            s.close()
            return
        }
        val token = info?.token ?: return
        controller.presentContentOnWindowArea(
            token = token,
            activity = activity,
            executor = ContextCompat.getMainExecutor(activity),
            windowAreaPresentationSessionCallback = this,
        )
    }

    fun close() {
        session?.close()
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        this.session = session
        active = true
        val iv = ImageView(session.context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            bitmap?.let { setImageBitmap(it) }
        }
        imageView = iv
        session.setContentView(iv)
    }

    override fun onSessionEnded(t: Throwable?) {
        session = null
        imageView = null
        active = false
        if (t != null) status = "背面表示エラー: ${t.message}"
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {}
}
