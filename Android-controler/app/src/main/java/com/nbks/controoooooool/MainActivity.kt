package com.nbks.controoooooool

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbks.controoooooool.model.Telemetry
import com.nbks.controoooooool.net.ConnectionConfig
import com.nbks.controoooooool.net.LinkState
import com.nbks.controoooooool.ui.display.DisplayLayouts
import com.nbks.controoooooool.ui.theme.ControooooooolTheme

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ControooooooolTheme {
                MonitorScreen(vm)
            }
        }
    }
}

@Composable
private fun MonitorScreen(vm: MonitorViewModel) {
    val state by vm.linkState.collectAsState()
    val telemetry by vm.telemetry.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Android 13+ の Wi-Fi 周辺機器権限を取ってから自動接続 (拒否されても接続自体は試す)。
    // 画面回転で再実行しないよう、起動時の 1 回だけにする
    var autoConnectDone by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.connect() }
    LaunchedEffect(Unit) {
        if (!autoConnectDone) {
            autoConnectDone = true
            permLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    val layouts = DisplayLayouts.all
    val selected = tab.coerceIn(layouts.indices)

    // 横画面ではノッチが左右に来るので safeDrawing で避ける
    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets.safeDrawing) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (landscape) {
                // 横画面: 高さが貴重なので、状態表示とタブを 1 行にまとめる
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBar(
                        state, telemetry, vm::connect, vm::disconnect,
                        Modifier.weight(1f),
                    )
                    if (layouts.size > 1) {
                        LayoutTabs(layouts.map { it.title }, selected, { tab = it }, Modifier.width(400.dp))
                    }
                }
            } else {
                StatusBar(state, telemetry, vm::connect, vm::disconnect, Modifier.fillMaxWidth())
                if (layouts.size > 1) {
                    LayoutTabs(layouts.map { it.title }, selected, { tab = it }, Modifier.fillMaxWidth())
                }
            }
            layouts[selected].Content(telemetry, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun LayoutTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    PrimaryTabRow(selectedTabIndex = selected, modifier = modifier) {
        titles.forEachIndexed { i, title ->
            Tab(selected = selected == i, onClick = { onSelect(i) }, text = { Text(title) })
        }
    }
}

@Composable
private fun StatusBar(
    state: LinkState,
    telemetry: Telemetry,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (color, text) = when (state) {
        LinkState.Idle -> Color.Gray to "未接続"
        LinkState.JoiningWifi -> Color(0xFFFFA000) to "${ConnectionConfig.SSID} に接続中…"
        LinkState.ConnectingTcp -> Color(0xFFFFA000) to "ESP32 に接続中…"
        LinkState.Connected ->
            if (telemetry.anyFresh) Color(0xFF2E7D32) to "受信中"
            else Color(0xFFD32F2F) to "ESP32 接続済み / CAN 受信なし"
        is LinkState.Error -> Color(0xFFD32F2F) to state.message
    }
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            text, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (state == LinkState.Idle || state is LinkState.Error) {
            Button(onClick = onConnect) { Text("接続") }
        } else {
            OutlinedButton(onClick = onDisconnect) { Text("切断") }
        }
    }
}
