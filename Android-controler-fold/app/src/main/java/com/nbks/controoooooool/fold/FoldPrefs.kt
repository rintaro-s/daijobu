package com.nbks.controoooooool.fold

import android.content.Context

/** Z Fold 向けの表示設定。アプリを閉じても残る */
class FoldPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("fold_prefs", Context.MODE_PRIVATE)

    /** 広い画面 (Z Fold を開いた状態) で、上下 2 段に分割して表示する */
    var split: Boolean
        get() = prefs.getBoolean(KEY_SPLIT, true)
        set(v) = prefs.edit().putBoolean(KEY_SPLIT, v).apply()

    private companion object {
        const val KEY_SPLIT = "split"
    }
}
