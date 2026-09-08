package net.snehesh.endocam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Environment
import android.util.Size
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App-private folder: Android/data/net.snehesh.endocam/files/<type>. Removed on uninstall. */
fun Context.mediaDir(type: String): File =
    (getExternalFilesDir(type) ?: File(filesDir, type)).apply { mkdirs() }

/** All recorded photos and videos, newest first. */
fun Context.mediaFiles(): List<File> =
    listOf(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES)
        .flatMap { mediaDir(it).listFiles()?.toList() ?: emptyList() }
        .sortedByDescending { it.lastModified() }

val File.isVideo: Boolean get() = extension.equals("mp4", ignoreCase = true)

/** Opens the system share sheet for one file through the FileProvider declared in the manifest. */
fun Context.share(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(if (file.isVideo) "video/mp4" else "image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Share ${file.name}"))
}

private fun thumbnail(file: File, px: Int): Bitmap? = runCatching {
    if (file.isVideo) ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
    else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
}.getOrNull()

/** Square thumbnail. Videos get a play mark. */
@Composable
fun Thumb(file: File, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { thumbnail(file, 256)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (file.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(files: List<File>, onOpen: (File) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { inner ->
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    "No photos or videos yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val photos = files.count { !it.isVideo }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "$photos photos · ${files.size - photos} videos",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(files, key = { it.path }) { f ->
                    Thumb(
                        f,
                        Modifier
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(onClickLabel = "Open ${f.name}") { onOpen(f) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(file: File, onShare: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
            if (file.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(file.path)
                            setOnPreparedListener { it.isLooping = true }
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val bmp by produceState<ImageBitmap?>(null, file) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                }
                bmp?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}
