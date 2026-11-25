package com.webbox.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

data class Site(
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

private fun defaultSites() = listOf(
    Site(name = "小黑盒", url = "https://www.xiaoheihe.cn", category = "常用"),
    Site(name = "快手", url = "https://www.kuaishou.com", category = "常用"),
    Site(name = "抖音", url = "https://www.douyin.com", category = "常用")
)

private fun loadSites(context: Context): List<Site> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SITES, null)
        ?: return defaultSites()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Site(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name"),
                url = o.optString("url"),
                category = o.optString("category", "其他"),
                favorite = o.optBoolean("favorite", false)
            )
        }
    } catch (_: Exception) { defaultSites() }
}

private fun saveSites(context: Context, sites: List<Site>) {
    val arr = JSONArray()
    sites.forEach { s ->
        arr.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("url", s.url)
            put("category", s.category); put("favorite", s.favorite)
        })
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_SITES, arr.toString()).apply()
}

private fun normalizedUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true))
        trimmed else "https://$trimmed"
}

private fun validUrl(input: String): Boolean = try {
    val uri = URI(normalizedUrl(input))
    !uri.host.isNullOrBlank() && (uri.scheme == "https" || uri.scheme == "http")
} catch (_: Exception) { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebBoxApp() {
    val context = LocalContext.current
    var sites by remember { mutableStateOf(loadSites(context)) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Site?>(null) }
    var opened by remember { mutableStateOf<Site?>(null) }
    var webUrl by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }

    fun update(newSites: List<Site>) {
        sites = newSites
        saveSites(context, newSites)
    }

    BackHandler(enabled = opened != null) { opened = null }
    BackHandler(enabled = opened == null && category != "全部") { category = "全部" }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF3157D5),
        secondary = androidx.compose.ui.graphics.Color(0xFF52627A),
        background = androidx.compose.ui.graphics.Color(0xFFF5F7FC),
        surface = androidx.compose.ui.graphics.Color.White
    )) {
        if (opened != null) {
            BrowserScreen(
                site = opened!!,
                initialUrl = webUrl,
                onBack = { opened = null }
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("网址百宝箱", style = MaterialTheme.typography.titleLarge)
                                Text("把常用网站放在一起", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            IconButton(onClick = { showAbout = true }) {
                                Icon(Icons.Default.Info, contentDescription = "关于")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { editing = null; showEditor = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("添加网址") }
                    )
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索网站名称或网址") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    val categories = listOf("全部", "常用", "工具", "购物", "学习", "其他")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(c) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val filtered = sites.filter {
                        (category == "全部" || it.category == category) &&
                            (it.name.contains(query, true) || it.url.contains(query, true))
                    }.sortedWith(compareByDescending<Site> { it.favorite }.thenBy { it.name })
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("还没有匹配的网站，点击“添加网址”开始")
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(filtered, key = { it.id }) { site ->
                                SiteCard(
                                    site = site,
                                    onOpen = { webUrl = site.url; opened = site },
                                    onFavorite = {
                                        update(sites.map { if (it.id == site.id) it.copy(favorite = !it.favorite) else it })
                                    },
                                    onEdit = { editing = site; showEditor = true },
                                    onDelete = { update(sites.filterNot { it.id == site.id }) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showEditor) {
            SiteEditorDialog(
                initial = editing,
                onDismiss = { showEditor = false },
                onSave = { name, url, cat ->
                    val normalized = normalizedUrl(url)
                    if (editing == null) {
                        update(sites + Site(name = name, url = normalized, category = cat))
                    } else {
                        update(sites.map {
                            if (it.id == editing!!.id) it.copy(name = name, url = normalized, category = cat) else it
                        })
                    }
                    showEditor = false
                }
            )
        }
        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("关于网址百宝箱") },
                text = { Text("WebBox 1.0.0\\n本应用用于收藏和整理网站链接。网站数据保存在本机，不会上传到服务器。打开的网站由各网站自行提供内容。") },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } }
            )
        }
    }
}

@Composable
private fun SiteCard(
    site: Site, onOpen: () -> Unit, onFavorite: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Text(site.name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(site.name, style = MaterialTheme.typography.titleMedium)
                Text(site.url, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(site.category, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (site.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "收藏")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteEditorDialog(
    initial: Site?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var cat by remember { mutableStateOf(initial?.category ?: "常用") }
    var expanded by remember { mutableStateOf(false) }
    val categories = listOf("常用", "工具", "购物", "学习", "其他")
    val ok = name.isNotBlank() && validUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加网站" else "编辑网站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("网站名称") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("网址") },
                    placeholder = { Text("例如 www.example.com") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = cat, onValueChange = {}, readOnly = true,
                        label = { Text("分类") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { cat = item; expanded = false })
                        }
                    }
                }
                if (url.isNotBlank() && !validUrl(url)) {
                    Text("请输入有效网址，例如 https://example.com",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onSave(name.trim(), url.trim(), cat) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
    site: Site, initialUrl: String, onBack: () -> Unit
) {
    var current by remember(site.id) { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(site.name, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                IconButton(onClick = { browser?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) current = url
                        }
                    }
                    loadUrl(initialUrl)
                }
            },
            update = { view -> if (view.url.isNullOrBlank()) view.loadUrl(initialUrl) }
        )
    }
}

