package com.nbks.controoooooool.fold

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 端末内の画像を新しい順に並べて、タップで選ぶ (READ_MEDIA_IMAGES が許可済みであること) */
@Composable
fun ImagePickerDialog(onPick: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val images by produceState<List<Uri>?>(null) {
        value = withContext(Dispatchers.IO) { queryImages(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("壁紙にする画像を選ぶ") },
        text = {
            val list = images
            when {
                list == null -> Text("読み込み中…")
                list.isEmpty() -> Text("画像が見つかりません")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                ) {
                    items(list, key = { it.toString() }) { uri ->
                        Thumb(uri, Modifier.padding(2.dp).aspectRatio(1f).clickable { onPick(uri) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun queryImages(context: android.content.Context, limit: Int = 400): List<Uri> {
    val out = ArrayList<Uri>()
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    runCatching {
        context.contentResolver.query(
            base, arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && out.size < limit) out.add(ContentUris.withAppendedId(base, c.getLong(idCol)))
        }
    }
    return out
}
