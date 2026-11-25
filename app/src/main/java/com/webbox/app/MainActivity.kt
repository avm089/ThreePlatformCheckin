package com.webbox.app

import android.annotation.SuppressLint
import android.content.Context
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
    var toolsVisible by remember { mutableStateOf(false) }
    var vpnVisible by remember { mutableStateOf(false) }
    var adBlockEnabled by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AD_BLOCK, true)) }
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
            BrowserScreen(site = browserSite!!, adBlockEnabled = adBlockEnabled, customAdDomains = customAdDomains, onBack = { browserSite = null })
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
                                Icon(Icons.Default.Tune, contentDescription = "功能设置")
                            }
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
                Box(Modifier.fillMaxSize()) {
                wallpaperBitmap?.let { Image(bitmap = it, contentDescription = "自定义壁纸", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.34f) }
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
private fun BrowserScreen(site: Site, adBlockEnabled: Boolean, customAdDomains: List<String>, onBack: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember(site.url) { mutableStateOf(site.url) }
    var manualPickEnabled by remember(site.url) { mutableStateOf(false) }
    val context = LocalContext.current
    val elementBridge = remember(site.id) { AdElementBridge { host, selector ->
        if (host.isNotBlank() && selector.isNotBlank()) saveElementRule(context, host.lowercase(), selector)
        Toast.makeText(context, "已屏蔽此广告区域", Toast.LENGTH_SHORT).show()
    } }

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
                            val host = request?.url?.host?.lowercase().orEmpty()
                            val blocked = adBlockEnabled && (adDomains + customAdDomains).any { host == it || host.endsWith(".$it") }
                            return if (blocked) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            else super.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) address = url
                            val host = Uri.parse(url ?: site.url).host.orEmpty().lowercase()
                            val rulesJson = JSONArray(loadElementRules(context, host)).toString()
                            val setupScript = """(function(){if(window.__webboxPickerInstalled)return;window.__webboxPickerInstalled=true;window.__webboxPickMode=false;function makeSelector(el){if(el.id)return "#"+CSS.escape(el.id);var parts=[];while(el&&el.nodeType===1&&el!==document.body){var part=el.tagName.toLowerCase();if(el.className&&typeof el.className==="string"){var cls=el.className.trim().split(/\\s+/).filter(Boolean)[0];if(cls)part+="."+CSS.escape(cls)}var parent=el.parentElement;if(parent){var same=Array.from(parent.children).filter(x=>x.tagName===el.tagName);if(same.length>1)part+=":nth-of-type("+(same.indexOf(el)+1)+")"}parts.unshift(part);el=parent;if(el&&el.id){parts.unshift("#"+CSS.escape(el.id));break}}return parts.join(" > ")}document.addEventListener("click",function(e){if(!window.__webboxPickMode)return;var el=e.target;if(!el||el===document.body)return;e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();var selector=makeSelector(el);el.style.setProperty("display","none","important");if(window.WebBoxBridge)window.WebBoxBridge.block(location.hostname,selector)},true);window.__webboxSetPickMode=function(v){window.__webboxPickMode=!!v;document.documentElement.style.cursor=v?"crosshair":""};window.__webboxApplyRules=function(rules){(rules||[]).forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(el){el.style.setProperty("display","none","important")})}catch(e){}})}})();window.__webboxApplyRules($rulesJson);window.__webboxSetPickMode($manualPickEnabled);"""
                            view?.evaluateJavascript(setupScript, null)
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
}
