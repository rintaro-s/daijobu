package com.nbks.controoooooool

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import com.nbks.controoooooool.record.RecordCodec
import com.nbks.controoooooool.record.RecordMode
import com.nbks.controoooooool.ui.RecordScreen
import com.nbks.controoooooool.ui.SettingsDialog
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
    val records by vm.records.collectAsState()
    val mode by vm.mode.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 「入った」ボタン: 押した瞬間のサーボ角度と速度を記録する
    val onMark: () -> Unit = {
        val r = vm.markIn()
        if (r != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(
                context,
                "記録 #${vm.records.value.size} [${r.mode.short}] " +
                    "角度 ${r.angleDeg?.let { "$it°" } ?: "--"} / 速度 ${r.pwmPermille ?: "--"}" +
                    if (r.stale) " (前回値)" else "",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val onShare: () -> Unit = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "記録 (${records.size} 件)")
            putExtra(Intent.EXTRA_TEXT, RecordCodec.csv(records))
        }
        context.startActivity(Intent.createChooser(send, "記録 CSV を共有"))
    }
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
    // 末尾に「記録」タブを足す (表示レイアウトとは別物なので、ここで特別扱いする)
    val titles = layouts.map { it.title } + "記録"
    val selected = tab.coerceIn(titles.indices)

    if (showSettings) {
        SettingsDialog(
            mode = mode,
            recordCount = records.size,
            onSelectMode = vm::setMode,
            onClearAll = vm::clearRecords,
            onDismiss = { showSettings = false },
        )
    }

    // 横画面ではノッチが左右に来るので safeDrawing で避ける
    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets.safeDrawing) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (landscape) {
                // 横画面: 高さが貴重なので、状態表示とタブを 1 行にまとめる
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBar(
                        state, telemetry, mode, vm::connect, vm::disconnect, onMark, { showSettings = true },
                        Modifier.weight(1f),
                    )
                    LayoutTabs(titles, selected, { tab = it }, Modifier.width(440.dp))
                }
            } else {
                StatusBar(
                    state, telemetry, mode, vm::connect, vm::disconnect, onMark, { showSettings = true },
                    Modifier.fillMaxWidth(),
                )
                LayoutTabs(titles, selected, { tab = it }, Modifier.fillMaxWidth())
            }
            if (selected < layouts.size) {
                layouts[selected].Content(telemetry, Modifier.fillMaxWidth().weight(1f))
            } else {
                RecordScreen(records, mode, onShare, vm::delete, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private val HeaderButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

@Composable
private fun LayoutTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    PrimaryScrollableTabRow(selectedTabIndex = selected, modifier = modifier, edgePadding = 0.dp) {
        titles.forEachIndexed { i, title ->
            // 既定の高さ (48dp) より薄くして、中身の表示領域を広げる
            Tab(
                selected = selected == i, onClick = { onSelect(i) },
                modifier = Modifier.height(34.dp),
                text = { Text(title, fontSize = 13.sp, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun StatusBar(
    state: LinkState,
    telemetry: Telemetry,
    mode: RecordMode,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onMark: () -> Unit,
    onSettings: () -> Unit,
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
        modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text, Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // 記録のタグ (設定で切り替え) と「入った」ボタン。どのタブからでも押せる
        Text(mode.short, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onMark,
            modifier = Modifier.height(30.dp),
            contentPadding = HeaderButtonPadding,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
        ) { Text("入った", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        TextButton(onClick = onSettings, modifier = Modifier.height(30.dp), contentPadding = HeaderButtonPadding) {
            Text("設定", fontSize = 13.sp)
        }
        if (state == LinkState.Idle || state is LinkState.Error) {
            Button(onClick = onConnect, modifier = Modifier.height(30.dp), contentPadding = HeaderButtonPadding) {
                Text("接続", fontSize = 13.sp)
            }
        } else {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.height(30.dp), contentPadding = HeaderButtonPadding) {
                Text("切断", fontSize = 13.sp)
            }
        }
    }
}
