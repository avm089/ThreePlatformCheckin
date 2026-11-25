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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
private val categories = listOf("全部", "常用", "工具", "购物", "学习", "其他")

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
        .getString(KEY_SITES, null) ?: return listOf(
        Site(name = "小黑盒", url = "https://www.xiaoheihe.cn", category = "常用"),
        Site(name = "快手", url = "https://www.kuaishou.com", category = "常用"),
        Site(name = "抖音", url = "https://www.douyin.com", category = "常用")
    )
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Site(
                id = item.optString("id", UUID.randomUUID().toString()),
                name = item.optString("name"),
                url = item.optString("url"),
                category = item.optString("category", "其他"),
                favorite = item.optBoolean("favorite", false)
            )
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

    fun updateSites(next: List<Site>) {
        sites = next
        saveSites(context, next)
    }

    BackHandler(enabled = browserSite != null) { browserSite = null }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF3157D5))) {
        if (browserSite != null) {
            BrowserScreen(site = browserSite!!, onBack = { browserSite = null })
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("网址百宝箱")
                                Text("常用网站，随时打开", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        actions = {
                            IconButton(onClick = { aboutVisible = true }) {
                                Icon(Icons.Default.Info, contentDescription = "关于")
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
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                ) {
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

        if (aboutVisible) {
            AlertDialog(
                onDismissRequest = { aboutVisible = false },
                title = { Text("关于网址百宝箱") },
                text = { Text("WebBox 1.0.0\n网站列表仅保存在本机。打开的网站内容由对应网站提供。") },
                confirmButton = {
                    TextButton(onClick = { aboutVisible = false }) { Text("知道了") }
                }
            )
        }
    }
}

@Composable
private fun SiteCard(
    site: Site,
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
                shape = RoundedCornerShape(13.dp),
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

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(site: Site, onBack: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(site.name, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )
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
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = WebViewClient()
                    loadUrl(site.url)
                }
            },
            update = { view -> webView = view }
        )
    }
}
