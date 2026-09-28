package com.nbks.controoooooool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbks.controoooooool.net.EspLink

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val link = EspLink(app, viewModelScope)

    val linkState = link.state
    val telemetry = link.telemetry

    fun connect() = link.start()
    fun disconnect() = link.stop()

    override fun onCleared() {
        link.stop()
    }
}
