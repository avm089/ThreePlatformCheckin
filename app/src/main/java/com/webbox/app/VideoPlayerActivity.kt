package com.webbox.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class VideoPlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_REFERER = "video_referer"
        const val EXTRA_USER_AGENT = "video_user_agent"
        const val EXTRA_COOKIE = "video_cookie"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "视频播放器" }
        if (url.isBlank() || Uri.parse(url).scheme !in listOf("http", "https")) {
            Toast.makeText(this, "视频地址无效，无法播放", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            VideoPlayerScreen(
                url, title,
                intent.getStringExtra(EXTRA_REFERER).orEmpty(),
                intent.getStringExtra(EXTRA_USER_AGENT).orEmpty().ifBlank { "Mozilla/5.0" },
                intent.getStringExtra(EXTRA_COOKIE).orEmpty(),
                onBack = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlayerScreen(
    url: String, title: String, referer: String, userAgent: String, cookie: String, onBack: () -> Unit
) {
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(1f) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorText = "播放失败：${error.errorCodeName}。该媒体可能需要网站专用授权或解码。"
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun downloadVideo() {
        try {
            val name = URLUtil.guessFileName(url, null, "video/mp4").ifBlank { "WebBox视频.mp4" }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription("WebBox 视频下载")
                setMimeType("video/mp4")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                addRequestHeader("User-Agent", userAgent)
                if (referer.isNotBlank()) addRequestHeader("Referer", referer)
                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "已加入系统下载队列：$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") } },
            actions = { IconButton(onClick = { downloadVideo() }) { Icon(Icons.Default.Download, contentDescription = "下载视频") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx -> PlayerView(ctx).apply { useController = true; keepScreenOn = true; this.player = player } },
                update = { it.player = player }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { speed = value; player.setPlaybackSpeed(value) },
                        label = { Text("${value}x") }
                    )
                }
            }
            Button(onClick = { downloadVideo() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("下载当前视频")
            }
            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            Text(
                "支持可直接访问的 MP4 等媒体地址；DRM 加密、blob 临时流和部分分段流可能无法直接下载。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
