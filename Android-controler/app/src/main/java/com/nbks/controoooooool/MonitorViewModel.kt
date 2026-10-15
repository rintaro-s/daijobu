package com.nbks.controoooooool

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.net.EspLink
import com.nbks.controoooooool.record.RecordMode
import com.nbks.controoooooool.record.RecordStore
import com.nbks.controoooooool.record.ShotRecord
import com.nbks.controoooooool.ui.display.DebugSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val link = EspLink(app, viewModelScope)
    private val store = RecordStore(app)

    val linkState = link.state
    val telemetry = link.telemetry

    private val _records = MutableStateFlow(store.loadRecords())
    /** 古い順 (一覧では新しい順に並べて表示する) */
    val records: StateFlow<List<ShotRecord>> = _records.asStateFlow()

    private val _mode = MutableStateFlow(store.loadMode())
    val mode: StateFlow<RecordMode> = _mode.asStateFlow()

    private var lastMarkMs = 0L

    fun connect() = link.start()
    fun disconnect() = link.stop()

    fun setMode(mode: RecordMode) {
        _mode.value = mode
        store.saveMode(mode)
    }

    /**
     * 「入った」ボタン。押した瞬間のサーボ角度と速度 (発射 PWM の設定) を、現在のタグ付きで記録する。
     * 誤って連続で押しても二重に記録しないよう、0.4 秒以内の押下は無視する。
     * @return 記録した内容。無視したときは null
     */
    fun markIn(): ShotRecord? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMarkMs < DEBOUNCE_MS) return null
        lastMarkMs = now

        val t = telemetry.value
        val angle = pick(t, "SHOT.angle", "SCMD.angle")
        val pwm = pick(t, "SHOT.pwm", "SCMD.pwm")
        val preset = pick(t, "SHOT.preset", "SCMD.preset")
        val rec = ShotRecord(
            timeMs = System.currentTimeMillis(),
            mode = _mode.value,
            angleDeg = angle.value,
            pwmPermille = pwm.value,
            preset = preset.value,
            // 値が取れなかった / 通信途絶中の前回値だった場合は、そう分かるように印を付ける
            stale = !(angle.fresh && pwm.fresh),
        )
        val next = _records.value + rec
        _records.value = next
        store.saveRecords(next)
        return rec
    }

    fun delete(timeMs: Long) {
        val next = _records.value.filterNot { it.timeMs == timeMs }
        _records.value = next
        store.saveRecords(next)
    }

    fun clearRecords() {
        _records.value = emptyList()
        store.saveRecords(emptyList())
    }

    private class Picked(val value: Int?, val fresh: Boolean)

    /** 0x310 (操作main の指令状態) を優先し、無ければ 0x150。受信が新鮮かどうかも返す */
    private fun pick(t: Telemetry, key310: String, key150: String): Picked {
        val shot = DebugSpec.units.first { it.prefix == "SHOT" }
        val cmd = DebugSpec.units.first { it.prefix == "SCMD" }
        t.value(key310)?.let { return Picked(it.toInt(), DebugSpec.link(t, shot) == DebugSpec.Link.Ok) }
        t.value(key150)?.let { return Picked(it.toInt(), DebugSpec.link(t, cmd) == DebugSpec.Link.Ok) }
        return Picked(null, false)
    }

    override fun onCleared() {
        link.stop()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
