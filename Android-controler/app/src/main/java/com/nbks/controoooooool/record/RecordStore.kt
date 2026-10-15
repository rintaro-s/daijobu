package com.nbks.controoooooool.record

import android.content.Context

/** 記録と設定 (タグ) を端末に保存する。アプリを閉じても残る */
class RecordStore(context: Context) {
    private val prefs = context.getSharedPreferences("shot_records", Context.MODE_PRIVATE)

    fun loadRecords(): List<ShotRecord> = RecordCodec.decode(prefs.getString(KEY_RECORDS, null))

    fun saveRecords(list: List<ShotRecord>) {
        prefs.edit().putString(KEY_RECORDS, RecordCodec.encode(list)).apply()
    }

    fun loadMode(): RecordMode = RecordMode.parse(prefs.getString(KEY_MODE, null))

    fun saveMode(mode: RecordMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val KEY_MODE = "mode"
    }
}
