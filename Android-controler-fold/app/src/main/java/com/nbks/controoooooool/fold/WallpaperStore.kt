package com.nbks.controoooooool.fold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 背面ディスプレイに出す壁紙画像の保存と読み込み。
 * 選んだ画像はアプリ専用の領域 (files/cover_wallpaper.jpg) にコピーするので、元の画像を消しても残る。
 * 大きすぎるとメモリを食うので、長辺 MAX_SIDE に縮小して保存する。
 */
object WallpaperStore {
    private const val TAG = "WallpaperStore"
    private const val FILE = "cover_wallpaper.jpg"
    private const val MAX_SIDE = 2400

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** 選んだ画像を縮小してコピーする。失敗したら false (既存の壁紙はそのまま) */
    fun saveFrom(context: Context, uri: Uri): Boolean = try {
        // ImageDecoder は JPEG / PNG / HEIF / WebP を読め、写真の回転 (EXIF) も反映する
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            // compress() するのでハードウェアビットマップにしない
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longSide = maxOf(w, h)
            if (longSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        val tmp = File(context.filesDir, "$FILE.tmp")
        val ok = tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        if (!ok) {
            tmp.delete()
            Log.w(TAG, "compress failed: $uri")
            false
        } else {
            // 書き終わってから差し替える (途中で失敗しても、前の壁紙を壊さない)
            file(context).delete()
            val renamed = tmp.renameTo(file(context))
            Log.i(TAG, "saved $uri -> ${file(context)} (${file(context).length()} bytes) renamed=$renamed")
            renamed
        }
    } catch (e: Exception) {
        Log.w(TAG, "saveFrom failed: $uri", e)
        false
    }

    fun delete(context: Context) {
        file(context).delete()
    }
}
