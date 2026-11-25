package com.webbox.app

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class VideoViewPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(VideoPlayerActivity.EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(VideoPlayerActivity.EXTRA_TITLE).orEmpty().ifBlank { "VideoView 播放器" }
        val referer = intent.getStringExtra(VideoPlayerActivity.EXTRA_REFERER).orEmpty()
        val userAgent = intent.getStringExtra(VideoPlayerActivity.EXTRA_USER_AGENT).orEmpty()
        val cookie = intent.getStringExtra(VideoPlayerActivity.EXTRA_COOKIE).orEmpty()
        if (url.isBlank() || Uri.parse(url).scheme !in listOf("http", "https")) {
            finish()
            return
        }
        setContent {
            var videoView by remember { mutableStateOf<VideoView?>(null) }
            DisposableEffect(Unit) {
                onDispose { videoView?.stopPlayback() }
            }
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") } }
                )
            }) { padding ->
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(Uri.parse(url), buildMap {
                                if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                                if (referer.isNotBlank()) put("Referer", referer)
                                if (cookie.isNotBlank()) put("Cookie", cookie)
                            })
                            setOnPreparedListener { it.start() }
                            videoView = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    }
                )
            }
        }
    }
}
