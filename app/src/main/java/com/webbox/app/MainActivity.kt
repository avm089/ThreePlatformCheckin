package com.webbox.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.graphics.BitmapFactory
import android.webkit.URLUtil
import android.widget.Toast
import java.io.ByteArrayInputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.delay

private data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val category: String = "其他",
    val favorite: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WebBoxApp() }
    }
}

private const val PREFS = "webbox_store"
private const val KEY_SITES = "sites"
private const val KEY_AD_BLOCK = "ad_block"
private const val KEY_FORCE_CUSTOM_PLAYER = "force_custom_player"
private const val KEY_CUSTOM_AD_DOMAINS = "custom_ad_domains"
private const val KEY_VPN_LINKS = "vpn_links"
private const val KEY_AD_ELEMENT_RULES = "ad_element_rules"
private const val KEY_WALLPAPER_URI = "wallpaper_uri"
private const val KEY_THEME_INDEX = "theme_index"
private const val KEY_ICON_STYLE = "icon_style"
private val adDomains = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "amazon-adsystem.com", "scorecardresearch.com", "adsafeprotected.com", "adservice.google.com")
private val categories = listOf("全部", "常用", "工具", "购物", "学习", "其他")
private val themeColors = listOf(Color(0xFF3157D5), Color(0xFF16856B), Color(0xFF8B50B5), Color(0xFFE07835))

private fun loadCustomAdDomains(context: Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CUSTOM_AD_DOMAINS, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { normalizeAdDomain(array.optString(it)) }.distinct()
    } catch (_: Exception) { emptyList() }
}

private fun normalizeAdDomain(value: String): String? {
    val input = value.trim().lowercase().removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore(':').removePrefix("*.").trimEnd('.')
    val valid = input.length in 1..253 &&
        input.split('.').size >= 2 &&
        input.split('.').all { label ->
            label.length in 1..63 && label.firstOrNull()?.isLetterOrDigit() == true &&
                label.lastOrNull()?.isLetterOrDigit() == true &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    return if (valid) input else null
}

private fun saveCustomAdDomains(context: Context, domains: List<String>) {
    val array = JSONArray()
    domains.distinct().forEach { array.put(it) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_CUSTOM_AD_DOMAINS, array.toString()).apply()
}

private fun loadElementRules(context: Context, host: String): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AD_ELEMENT_RULES, null) ?: return emptyList()
    return try {
        val array = JSONObject(raw).optJSONArray(host) ?: return emptyList()
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.distinct()
    } catch (_: Exception) { emptyList() }
}

private fun saveElementRule(context: Context, host: String, selector: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val root = try { JSONObject(prefs.getString(KEY_AD_ELEMENT_RULES, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    val array = root.optJSONArray(host) ?: JSONArray()
    if ((0 until array.length()).none { array.optString(it) == selector }) array.put(selector)
    root.put(host, array)
    prefs.edit().putString(KEY_AD_ELEMENT_RULES, root.toString()).apply()
}

private class AdElementBridge(private val onBlock: (String, String) -> Unit) {
    @JavascriptInterface fun block(host: String, selector: String) { onBlock(host, selector) }
}

private data class MediaResource(val url: String, val type: String, val title: String)
private data class DownloadItem(val id: Long, val title: String, val status: Int, val downloaded: Long, val total: Long)

private fun loadDownloadItems(context: Context): List<DownloadItem> {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = manager.query(DownloadManager.Query()) ?: return emptyList()
    return cursor.use { c ->
        val result = mutableListOf<DownloadItem>()
        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val downloadedCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        while (c.moveToNext()) {
            result.add(DownloadItem(c.getLong(idCol), c.getString(titleCol) ?: "下载文件", c.getInt(statusCol), c.getLong(downloadedCol), c.getLong(totalCol)))
        }
        result.sortedByDescending { it.id }
    }
}

private class MediaSniffBridge(private val onFound: (String, String, String) -> Unit) {
    @JavascriptInterface fun found(url: String, type: String, title: String) { onFound(url, type, title) }
}

private fun normalizeUrl(value: String): String {
    val text = value.trim()
    return if (text.startsWith("https://", true) || text.startsWith("http://", true)) text
    else "https://$text"
}

private fun isValidUrl(value: String): Boolean = try {
    val uri = URI(normalizeUrl(value))
    (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) { false }

private fun loadSites(context: Context): List<Site> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SITES, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val name = item.optString("name").trim()
            val url = item.optString("url").trim().lowercase()
            // Remove the three bundled shortcuts from existing saved lists as well.
            if (name in setOf("小黑盒", "快手", "抖音") ||
                url.contains("xiaoheihe.cn") || url.contains("kuaishou.com") || url.contains("douyin.com")
            ) return@mapNotNull null
            Site(
                id = item.optString("id", UUID.randomUUID().toString()),
                name = name,
                url = item.optString("url"),
                category = item.optString("category", "其他"),
                favorite = item.optBoolean("favorite", false)
            )
        }.also { cleaned ->
            if (cleaned.size != array.length()) saveSites(context, cleaned)
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveSites(context: Context, sites: List<Site>) {
    val array = JSONArray()
    sites.forEach { site ->
        array.put(JSONObject().apply {
            put("id", site.id)
            put("name", site.name)
            put("url", site.url)
            put("category", site.category)
            put("favorite", site.favorite)
        })
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_SITES, array.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebBoxApp() {
    val context = LocalContext.current
    var sites by remember { mutableStateOf(loadSites(context)) }
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("全部") }
    var editorVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Site?>(null) }
    var browserSite by remember { mutableStateOf<Site?>(null) }
    var aboutVisible by remember { mutableStateOf(false) }
    var mainDownloadsVisible by remember { mutableStateOf(false) }
    var toolsVisible by remember { mutableStateOf(false) }
    var vpnVisible by remember { mutableStateOf(false) }
    var adBlockEnabled by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AD_BLOCK, true)) }
    var forceCustomPlayer by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_CUSTOM_PLAYER, false)) }
    var customAdDomains by remember { mutableStateOf(loadCustomAdDomains(context)) }
    var adRulesVisible by remember { mutableStateOf(false) }
    var appearanceVisible by remember { mutableStateOf(false) }
    var wallpaperUri by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WALLPAPER_URI, null)) }
    var themeIndex by remember { mutableIntStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME_INDEX, 0).coerceIn(0, themeColors.lastIndex)) }
    var iconStyle by remember { mutableIntStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ICON_STYLE, 0).coerceIn(0, 2)) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            wallpaperUri = uri.toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WALLPAPER_URI, wallpaperUri).apply()
        }
    }
    val wallpaperBitmap = remember(wallpaperUri) {
        try { wallpaperUri?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() } } }
        catch (_: Exception) { null }
    }
    var vpnLinks by remember { mutableStateOf(loadVpnLinks(context)) }

    fun updateSites(next: List<Site>) {
        sites = next
        saveSites(context, next)
    }

    BackHandler(enabled = browserSite != null) { browserSite = null }

    MaterialTheme(colorScheme = lightColorScheme(primary = themeColors[themeIndex])) {
        if (browserSite != null) {
            BrowserScreen(site = browserSite!!, adBlockEnabled = adBlockEnabled, customAdDomains = customAdDomains, forceCustomPlayer = forceCustomPlayer, onBack = { browserSite = null })
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("网址百宝箱")
                                Text("常用网站，随时打开", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        actions = {
                            IconButton(onClick = { toolsVisible = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { editing = null; editorVisible = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("添加网址") }
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFEAF2FF), Color(0xFFF7FAFF), Color(0xFFEFF5FF))))) {
                wallpaperBitmap?.let { Image(bitmap = it, contentDescription = "自定义壁纸", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.34f) }
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Brush.linearGradient(listOf(Color(0xFF123B9A), Color(0xFF087ED6), Color(0xFF26C6DA))))
                                .padding(horizontal = 18.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("探索你的网络世界", color = Color.White, style = MaterialTheme.typography.titleLarge)
                                Text("收藏常用网站 · 一键快速访问", color = Color(0xFFE1F5FE), style = MaterialTheme.typography.bodySmall)
                                Text("${sites.size} 个已保存网站", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.labelMedium)
                            }
                            Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.16f)) {
                                Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.padding(14.dp).size(42.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索网站名称或网址") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val filtered = sites.filter { site ->
                        (selectedCategory == "全部" || site.category == selectedCategory) &&
                            (site.name.contains(search, true) || site.url.contains(search, true))
                    }.sortedWith(compareByDescending<Site> { it.favorite }.thenBy { it.name })

                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有找到网站，点击“添加网址”创建")
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(filtered, key = { it.id }) { site ->
                                SiteCard(
                                    site = site,
                                    iconStyle = iconStyle,
                                    onOpen = { browserSite = site },
                                    onFavorite = {
                                        updateSites(sites.map {
                                            if (it.id == site.id) it.copy(favorite = !it.favorite) else it
                                        })
                                    },
                                    onEdit = { editing = site; editorVisible = true },
                                    onDelete = { updateSites(sites.filterNot { it.id == site.id }) }
                                )
                            }
                        }
                    }
                }
                }
            }
        }

        if (editorVisible) {
            SiteEditorDialog(
                initial = editing,
                onDismiss = { editorVisible = false },
                onSave = { name, url, category ->
                    val savedUrl = normalizeUrl(url)
                    val current = editing
                    if (current == null) {
                        updateSites(sites + Site(name = name, url = savedUrl, category = category))
                    } else {
                        updateSites(sites.map {
                            if (it.id == current.id) it.copy(name = name, url = savedUrl, category = category)
                            else it
                        })
                    }
                    editorVisible = false
                }
            )
        }


        if (toolsVisible) {
            AlertDialog(
                onDismissRequest = { toolsVisible = false },
                title = { Text("浏览器与隐私设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("网页广告屏蔽", style = MaterialTheme.typography.titleMedium)
                                Text("拦截常见广告与跟踪域名；部分网站广告可能仍会出现", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = adBlockEnabled, onCheckedChange = {
                                adBlockEnabled = it
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AD_BLOCK, it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("强制接管网站视频播放器", style = MaterialTheme.typography.titleMedium)
                                Text("检测网页视频并尝试交给 WebBox ExoPlayer 播放；部分受保护视频无法接管", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = forceCustomPlayer, onCheckedChange = {
                                forceCustomPlayer = it
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FORCE_CUSTOM_PLAYER, it).apply()
                            })
                        }
                        OutlinedButton(
                            onClick = { toolsVisible = false; mainDownloadsVisible = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("下载管理")
                        }
                        OutlinedButton(
                            onClick = { toolsVisible = false; adRulesVisible = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("自定义屏蔽规则（${customAdDomains.size}）")
                        }
                        OutlinedButton(onClick = { toolsVisible = false; appearanceVisible = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Palette, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("壁纸与外观图标")
                        }
                        Divider()
                        Text("隐私防护", style = MaterialTheme.typography.titleMedium)
                        Text("清除内置浏览器 Cookie、缓存和网页存储。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            Toast.makeText(context, "浏览器 Cookie 和网页数据已清除", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text("清除浏览数据") }
                        Divider()
                        Text("VPN 链接管理", style = MaterialTheme.typography.titleMedium)
                        Text("添加和保存你自己的 VPN 配置/订阅链接。保存链接本身不会建立 VPN 隧道，真实连接需要对应协议内核。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { toolsVisible = false; vpnVisible = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("管理 VPN 链接（${vpnLinks.size}）")
                        }
                        OutlinedButton(onClick = { toolsVisible = false; aboutVisible = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("关于 WebBox")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { toolsVisible = false }) { Text("完成") } }
            )
        }

        if (appearanceVisible) {
            AppearanceDialog(
                wallpaperSet = wallpaperUri != null,
                themeIndex = themeIndex,
                iconStyle = iconStyle,
                onPickWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                onResetWallpaper = {
                    wallpaperUri = null
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_WALLPAPER_URI).apply()
                },
                onThemeChange = { themeIndex = it; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THEME_INDEX, it).apply() },
                onIconStyleChange = { iconStyle = it; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ICON_STYLE, it).apply() },
                onDismiss = { appearanceVisible = false }
            )
        }

        if (adRulesVisible) {
            AdBlockRulesDialog(
                domains = customAdDomains,
                onSave = { next ->
                    customAdDomains = next
                    saveCustomAdDomains(context, next)
                },
                onDismiss = { adRulesVisible = false }
            )
        }

        if (vpnVisible) {
            VpnLinksDialog(
                links = vpnLinks,
                onSave = { next ->
                    vpnLinks = next
                    val array = JSONArray()
                    next.forEach { array.put(it) }
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VPN_LINKS, array.toString()).apply()
                },
                onDismiss = { vpnVisible = false }
            )
        }

        if (mainDownloadsVisible) {
            DownloadManagerDialog(onDismiss = { mainDownloadsVisible = false })
        }

        if (aboutVisible) {
            AlertDialog(
                onDismissRequest = { aboutVisible = false },
                title = { Text("关于网址百宝箱") },
                text = { Text("WebBox 1.6.0\n网站列表仅保存在本机。打开的网站内容由对应网站提供。") },
                confirmButton = {
                    TextButton(onClick = { aboutVisible = false }) { Text("知道了") }
                }
            )
        }
    }
}

@Composable
private fun DownloadManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var downloadItems by remember { mutableStateOf(loadDownloadItems(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            downloadItems = loadDownloadItems(context)
            delay(1000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载管理（${downloadItems.size}）") },
        text = {
            if (downloadItems.isEmpty()) {
                Text("还没有下载任务", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(downloadItems, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            val statusText = when (item.status) {
                                DownloadManager.STATUS_PENDING -> "等待下载"
                                DownloadManager.STATUS_RUNNING -> "正在下载"
                                DownloadManager.STATUS_PAUSED -> "已暂停"
                                DownloadManager.STATUS_SUCCESSFUL -> "下载完成"
                                DownloadManager.STATUS_FAILED -> "下载失败"
                                else -> "状态未知"
                            }
                            Text(
                                statusText + if (item.total > 0) " · ${android.text.format.Formatter.formatFileSize(context, item.downloaded)} / ${android.text.format.Formatter.formatFileSize(context, item.total)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING || item.status == DownloadManager.STATUS_PAUSED) {
                                if (item.total > 0) LinearProgressIndicator(
                                    progress = { (item.downloaded.toFloat() / item.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                ) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                                    TextButton(onClick = {
                                        try {
                                            val uri = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(item.id)
                                            if (uri != null) {
                                                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(Intent.createChooser(intent, "打开下载文件"))
                                            } else Toast.makeText(context, "找不到已下载文件", Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
                                        }
                                    }) { Text("打开") }
                                }
                                TextButton(onClick = {
                                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
                                    downloadItems = loadDownloadItems(context)
                                }) { Text("移除") }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SiteCard(
    site: Site,
    iconStyle: Int,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = when (iconStyle) { 1 -> CircleShape; 2 -> RoundedCornerShape(4.dp); else -> RoundedCornerShape(13.dp) },
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Text(
                        site.name.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(site.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    site.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    site.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (site.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "收藏"
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteEditorDialog(
    initial: Site?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var url by remember(initial?.id) { mutableStateOf(initial?.url ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "常用") }
    var expanded by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && isValidUrl(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加网站" else "编辑网站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("网站名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址") },
                    placeholder = { Text("例如 www.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分类") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categories.filter { it != "全部" }.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = { category = item; expanded = false }
                            )
                        }
                    }
                }
                if (url.isNotBlank() && !isValidUrl(url)) {
                    Text(
                        "请输入有效网址，例如 https://example.com",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name.trim(), url.trim(), category) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


@Composable
private fun AppearanceDialog(
    wallpaperSet: Boolean,
    themeIndex: Int,
    iconStyle: Int,
    onPickWallpaper: () -> Unit,
    onResetWallpaper: () -> Unit,
    onThemeChange: (Int) -> Unit,
    onIconStyleChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("壁纸与外观图标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("首页壁纸", style = MaterialTheme.typography.titleSmall)
                Text(if (wallpaperSet) "已设置自定义壁纸" else "当前使用默认背景", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickWallpaper, modifier = Modifier.weight(1f)) { Text("从相册选择") }
                    OutlinedButton(onClick = onResetWallpaper, enabled = wallpaperSet, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                }
                Divider()
                Text("主题颜色", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    themeColors.forEachIndexed { index, color ->
                        Surface(
                            modifier = Modifier.size(38.dp).clickable { onThemeChange(index) },
                            shape = CircleShape,
                            color = color,
                            border = if (themeIndex == index) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
                        ) {}
                    }
                }
                Divider()
                Text("网站图标形状", style = MaterialTheme.typography.titleSmall)
                listOf("圆角图标", "圆形图标", "方形图标").forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().clickable { onIconStyleChange(index) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = iconStyle == index, onClick = { onIconStyleChange(index) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun AdBlockRulesDialog(
    domains: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var newDomain by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义广告屏蔽") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入广告域名，例如 ads.example.com。也可以输入完整网址，程序会自动提取域名。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("广告域名或网址") },
                    placeholder = { Text("ads.example.com") }
                )
                Button(
                    onClick = {
                        val domain = normalizeAdDomain(newDomain)
                        when {
                            domain == null -> error = "域名格式不正确，请检查后重试"
                            domain in domains -> error = "这个域名已经添加过了"
                            else -> {
                                onSave(domains + domain)
                                newDomain = ""
                                error = ""
                            }
                        }
                    },
                    enabled = newDomain.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("添加屏蔽域名") }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Divider()
                Text("已添加规则（${domains.size}）", style = MaterialTheme.typography.titleSmall)
                if (domains.isEmpty()) {
                    Text("还没有自定义规则", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(domains, key = { it }) { domain ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(domain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { onSave(domains - domain) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除规则")
                                }
                            }
                        }
                    }
                }
                Text("规则仅在开启“网页广告屏蔽”时生效，并保存在本机。部分广告可能由网站自身加载，无法保证全部拦截。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}


private fun loadVpnLinks(context: Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VPN_LINKS, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun VpnLinksDialog(links: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var newLink by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的 VPN 链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newLink,
                    onValueChange = { newLink = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴 VPN 配置/订阅链接") },
                    placeholder = { Text("https://… 或协议分享链接") },
                    minLines = 2
                )
                Button(
                    onClick = {
                        val value = newLink.trim()
                        if (value.isNotBlank() && value !in links) onSave(links + value)
                        newLink = ""
                    },
                    enabled = newLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存链接") }
                if (links.isEmpty()) Text("暂时没有 VPN 链接", style = MaterialTheme.typography.bodySmall)
                else LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(links) { link ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(link, modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { onSave(links - link) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除链接")
                            }
                        }
                    }
                }
                Text("提示：目前此处用于保存和管理链接，尚未集成 VPN 协议内核，因此不会显示虚假的连接状态。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(site: Site, adBlockEnabled: Boolean, customAdDomains: List<String>, forceCustomPlayer: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember(site.url) { mutableStateOf(site.url) }
    var manualPickEnabled by remember(site.url) { mutableStateOf(false) }
    var mediaResources by remember(site.url) { mutableStateOf(emptyList<MediaResource>()) }
    var mediaDialogVisible by remember { mutableStateOf(false) }
    var playerChoiceResource by remember { mutableStateOf<MediaResource?>(null) }
    var takeoverInProgress by remember { mutableStateOf(false) }
    var downloadDialogVisible by remember { mutableStateOf(false) }
    var downloadItems by remember { mutableStateOf(loadDownloadItems(context)) }
    val manualPickModeState = rememberUpdatedState(manualPickEnabled)
    val forcePlayerEnabledState = rememberUpdatedState(forceCustomPlayer)
    val elementBridge = remember(site.id) { AdElementBridge { host, selector ->
        if (host.isNotBlank() && selector.isNotBlank()) saveElementRule(context, host.lowercase(), selector)
        Toast.makeText(context, "已屏蔽此广告区域", Toast.LENGTH_SHORT).show()
    } }
    val mediaBridge = remember(site.id) { MediaSniffBridge { url, type, title ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val parsed = Uri.parse(url)
            if ((parsed.scheme == "http" || parsed.scheme == "https") && !parsed.host.isNullOrBlank()) {
                val item = MediaResource(url, type, title.ifBlank { "网页媒体文件" })
                mediaResources = (mediaResources.filterNot { it.url == url } + item).takeLast(100)
                if (type == "强制接管视频" && forcePlayerEnabledState.value && !takeoverInProgress) {
                    takeoverInProgress = true
                    try {
                        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_URL, url)
                            putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                            putExtra(VideoPlayerActivity.EXTRA_REFERER, address)
                            putExtra(VideoPlayerActivity.EXTRA_USER_AGENT, webView?.settings?.userAgentString ?: "Mozilla/5.0")
                            putExtra(VideoPlayerActivity.EXTRA_COOKIE, CookieManager.getInstance().getCookie(url) ?: "")
                        }
                        context.startActivity(intent)
                        Toast.makeText(context, "已尝试接管网页视频", Toast.LENGTH_SHORT).show()
                        takeoverInProgress = false
                    } catch (e: Exception) {
                        takeoverInProgress = false
                        Toast.makeText(context, "接管失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    } }

    LaunchedEffect(forceCustomPlayer) {
        webView?.evaluateJavascript("window.__webboxForcePlayerEnabled=$forceCustomPlayer", null)
    }

    fun downloadMedia(item: MediaResource) {
        try {
            val fileName = URLUtil.guessFileName(item.url, null, null)
            val request = DownloadManager.Request(Uri.parse(item.url)).apply {
                setTitle(fileName)
                setDescription("WebBox 正在下载媒体文件")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                addRequestHeader("User-Agent", webView?.settings?.userAgentString ?: "Mozilla/5.0")
                if (address.startsWith("http")) addRequestHeader("Referer", address)
                CookieManager.getInstance().getCookie(item.url)?.let { addRequestHeader("Cookie", it) }
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "已加入下载：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(downloadDialogVisible) {
        while (downloadDialogVisible) {
            downloadItems = loadDownloadItems(context)
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(site.name, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "网页后退")
                }
                IconButton(onClick = { if (webView?.canGoForward() == true) webView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "网页前进")
                }
                IconButton(onClick = { manualPickEnabled = !manualPickEnabled; webView?.evaluateJavascript("window.__webboxSetPickMode && window.__webboxSetPickMode($manualPickEnabled)", null); Toast.makeText(context, if (manualPickEnabled) "点选屏蔽已开启：点击网页广告区域" else "已退出点选屏蔽", Toast.LENGTH_SHORT).show() }) {
                    Icon(Icons.Default.Block, contentDescription = "手动点选屏蔽", tint = if (manualPickEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { downloadItems = loadDownloadItems(context); downloadDialogVisible = true }) {
                    Icon(Icons.Default.Download, contentDescription = "下载管理")
                }
                IconButton(onClick = {
                    mediaResources = emptyList()
                    mediaDialogVisible = true
                    webView?.evaluateJavascript("window.__webboxScanMedia && window.__webboxScanMedia()", null)
                    Toast.makeText(context, "正在扫描网页中的视频和文件链接", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.DownloadForOffline, contentDescription = "强制下载")
                }
                IconButton(onClick = { mediaDialogVisible = true; webView?.evaluateJavascript("window.__webboxScanMedia && window.__webboxScanMedia()", null) }) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = "资源嗅探")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入网址或搜索") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                val target = if (address.contains(" ") || (!address.contains(".") && !address.startsWith("http"))) {
                    "https://www.google.com/search?q=" + Uri.encode(address)
                } else normalizeUrl(address)
                address = target
                webView?.loadUrl(target)
            }) { Text("前往") }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    addJavascriptInterface(elementBridge, "WebBoxBridge")
                    addJavascriptInterface(mediaBridge, "WebBoxMediaBridge")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        try {
                            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            val request = DownloadManager.Request(Uri.parse(url)).apply {
                                setTitle(fileName)
                                setDescription("正在下载：$fileName")
                                setMimeType(mimeType)
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                addRequestHeader("User-Agent", userAgent)
                                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                            }
                            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            manager.enqueue(request)
                            Toast.makeText(context, "已加入系统下载任务", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    })
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestedUri = request?.url
                            val host = requestedUri?.host?.lowercase().orEmpty()
                            val blocked = adBlockEnabled && (adDomains + customAdDomains).any { host == it || host.endsWith(".$it") }
                            if (blocked) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                            // Observe media requests from the whole WebView, including iframe players.
                            // Only surface playable files/manifests; do not collect DRM license calls or tiny HLS segments.
                            val requestedUrl = requestedUri?.toString().orEmpty()
                            val path = requestedUri?.lastPathSegment?.lowercase().orEmpty()
                            val mediaKind = when {
                                path.endsWith(".m3u8") -> "网络嗅探 · HLS/M3U8"
                                path.endsWith(".mpd") -> "网络嗅探 · DASH/MPD"
                                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".mov") -> "网络嗅探 · 视频直链"
                                else -> null
                            }
                            if (mediaKind != null && requestedUrl.startsWith("https://") || mediaKind != null && requestedUrl.startsWith("http://")) {
                                mediaBridge.found(requestedUrl, mediaKind, "网页网络媒体")
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) address = url
                            val host = Uri.parse(url ?: site.url).host.orEmpty().lowercase()
                            val rulesJson = JSONArray(loadElementRules(context, host)).toString()
                            val setupScript = """(function(){if(window.__webboxPickerInstalled)return;window.__webboxPickerInstalled=true;window.__webboxPickMode=false;function makeSelector(el){if(el.id)return "#"+CSS.escape(el.id);var parts=[];while(el&&el.nodeType===1&&el!==document.body){var part=el.tagName.toLowerCase();if(el.className&&typeof el.className==="string"){var cls=el.className.trim().split(/\\s+/).filter(Boolean)[0];if(cls)part+="."+CSS.escape(cls)}var parent=el.parentElement;if(parent){var same=Array.from(parent.children).filter(x=>x.tagName===el.tagName);if(same.length>1)part+=":nth-of-type("+(same.indexOf(el)+1)+")"}parts.unshift(part);el=parent;if(el&&el.id){parts.unshift("#"+CSS.escape(el.id));break}}return parts.join(" > ")}document.addEventListener("click",function(e){if(!window.__webboxPickMode)return;var el=e.target;if(!el||el===document.body)return;e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();var selector=makeSelector(el);el.style.setProperty("display","none","important");if(window.WebBoxBridge)window.WebBoxBridge.block(location.hostname,selector)},true);window.__webboxSetPickMode=function(v){window.__webboxPickMode=!!v;document.documentElement.style.cursor=v?"crosshair":""};window.__webboxApplyRules=function(rules){(rules||[]).forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(el){el.style.setProperty("display","none","important")})}catch(e){}})}})();window.__webboxApplyRules($rulesJson);window.__webboxSetPickMode(${manualPickModeState.value});"""
                            view?.evaluateJavascript(setupScript, null)
                            val forcePlayerScript = """(function(){if(window.__webboxForcePlayerInstalled)return;window.__webboxForcePlayerInstalled=true;window.__webboxForcePlayerEnabled=${forceCustomPlayer};document.addEventListener("play",function(e){if(!window.__webboxForcePlayerEnabled)return;var v=e.target;if(!v||v.tagName!=="VIDEO")return;var u=v.currentSrc||v.src;if(!u||!/^https?:/i.test(u))return;v.pause();v.style.setProperty("visibility","hidden","important");v.style.setProperty("height","1px","important");if(window.WebBoxMediaBridge)window.WebBoxMediaBridge.found(u,"强制接管视频",document.title||"网页视频");},true);})();"""
                            view?.evaluateJavascript(forcePlayerScript, null)
                            val mediaScanScript = """(function(){if(window.__webboxMediaScannerInstalled){window.__webboxScanMedia&&window.__webboxScanMedia();return;}window.__webboxMediaScannerInstalled=true;var seen={};function send(u,t){try{if(!u||/^(blob:|data:|javascript:)/i.test(u))return;u=new URL(u,location.href).href;if(!/^https?:/i.test(u)||seen[u])return;if(!/\\.(mp4|webm|m4v|mov|m3u8|mpd|mp3|m4a|aac|ogg|wav|pdf|zip|rar|7z|apk|exe|dmg|docx?|xlsx?|pptx?|csv|txt|jpg|jpeg|png|gif|webp)(?:[?#]|$)/i.test(u)&&t.indexOf('媒体元素')!==0)return;seen[u]=1;if(window.WebBoxMediaBridge)window.WebBoxMediaBridge.found(u,t,document.title||'网页媒体文件');}catch(e){}}function scan(){document.querySelectorAll('video,audio').forEach(function(el){send(el.currentSrc||el.src,'媒体元素');el.querySelectorAll('source').forEach(function(s){send(s.src,'媒体元素');});});document.querySelectorAll('a[href]').forEach(function(a){send(a.href,'网页文件链接');});var entries=performance.getEntriesByType('resource')||[];entries.forEach(function(e){send(e.name,'网络媒体资源');});}window.__webboxScanMedia=scan;scan();var observer=new MutationObserver(scan);observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});setTimeout(function(){try{observer.disconnect();}catch(e){}},180000);})();"""
                            view?.evaluateJavascript(mediaScanScript, null)
                            super.onPageFinished(view, url)
                        }
                    }
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    loadUrl(site.url)
                }
            },
            update = { view -> webView = view }
        )
    }
    if (downloadDialogVisible) {
        AlertDialog(
            onDismissRequest = { downloadDialogVisible = false },
            title = { Text("下载管理（${downloadItems.size}）") },
            text = {
                if (downloadItems.isEmpty()) Text("还没有下载任务", style = MaterialTheme.typography.bodyMedium)
                else LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(downloadItems, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            val statusText = when (item.status) {
                                DownloadManager.STATUS_PENDING -> "等待下载"
                                DownloadManager.STATUS_RUNNING -> "正在下载"
                                DownloadManager.STATUS_PAUSED -> "已暂停"
                                DownloadManager.STATUS_SUCCESSFUL -> "下载完成"
                                DownloadManager.STATUS_FAILED -> "下载失败"
                                else -> "状态未知"
                            }
                            Text(statusText + if (item.total > 0) " · ${android.text.format.Formatter.formatFileSize(context, item.downloaded)} / ${android.text.format.Formatter.formatFileSize(context, item.total)}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING || item.status == DownloadManager.STATUS_PAUSED) {
                                if (item.total > 0) LinearProgressIndicator(progress = { (item.downloaded.toFloat() / item.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (item.status == DownloadManager.STATUS_SUCCESSFUL) TextButton(onClick = {
                                    try {
                                        val uri = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(item.id)
                                        if (uri != null) {
                                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            context.startActivity(Intent.createChooser(intent, "打开下载文件"))
                                        } else Toast.makeText(context, "找不到已下载文件", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) { Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show() }
                                }) { Text("打开") }
                                TextButton(onClick = {
                                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id)
                                    downloadItems = loadDownloadItems(context)
                                }) { Text("移除") }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { downloadDialogVisible = false }) { Text("关闭") } }
        )
    }
            if (playerChoiceResource != null) {
            val item = playerChoiceResource!!
            AlertDialog(
                onDismissRequest = { playerChoiceResource = null },
                title = { Text("选择播放器") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = {
                            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                                putExtra(VideoPlayerActivity.EXTRA_URL, item.url)
                                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                                putExtra(VideoPlayerActivity.EXTRA_REFERER, address)
                                putExtra(VideoPlayerActivity.EXTRA_USER_AGENT, webView?.settings?.userAgentString ?: "Mozilla/5.0")
                                putExtra(VideoPlayerActivity.EXTRA_COOKIE, CookieManager.getInstance().getCookie(item.url) ?: "")
                            }
                            context.startActivity(intent)
                            playerChoiceResource = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("1. WebBox 内置播放器（ExoPlayer）") }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setPackage("com.quark.browser")
                                    setDataAndType(Uri.parse(item.url), "video/*")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "未检测到夸克浏览器，请先安装夸克", Toast.LENGTH_LONG).show()
                            }
                            playerChoiceResource = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("2. 夸克播放器") }
                    }
                },
                confirmButton = { TextButton(onClick = { playerChoiceResource = null }) { Text("取消") } }
            )
        }

if (mediaDialogVisible) {
        AlertDialog(
            onDismissRequest = { mediaDialogVisible = false },
            title = { Text("网页资源嗅探（${mediaResources.size}）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("扫描当前网页可访问的媒体地址。优先支持 MP4、WebM、MP3 等直链；加密流、受保护内容和 blob 临时地址可能无法下载。", style = MaterialTheme.typography.bodySmall)
                    if (mediaResources.isEmpty()) {
                        Text("暂未发现可直接下载的媒体。请先播放网页视频，再打开资源嗅探。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(mediaResources, key = { it.url }) { item ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(item.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { playerChoiceResource = item }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Text("选择播放器")
                                        }
                                        TextButton(onClick = { downloadMedia(item) }) { Icon(Icons.Default.Download, contentDescription = null); Text("下载") }
                                    }
                                    Divider()
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mediaDialogVisible = false }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { mediaResources = emptyList(); webView?.evaluateJavascript("window.__webboxScanMedia && window.__webboxScanMedia()", null) }) { Text("重新扫描") } }
        )
    }
}