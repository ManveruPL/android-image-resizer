package pl.fehu.imageresizer.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val THUMBNAIL_PX = 256

/** Loads a small thumbnail via `ContentResolver.loadThumbnail` (no image library needed, §9.1). */
@Composable
fun Thumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_PX, THUMBNAIL_PX), CancellationSignal())
                } else {
                    // loadThumbnail is API 29+; API 28 falls back to a simple subsampled decode.
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(modifier = modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmapState.value?.let { bitmap ->
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(72.dp))
        }
    }
}
