package app.wayline.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayline.vpn.data.ConfigParser
import app.wayline.vpn.data.Server
import app.wayline.vpn.data.Subscription
import app.wayline.vpn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaylineApp(
    connected: Boolean,
    connecting: Boolean,
    error: String?,
    onConnect: (config: String, mode: String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("wayline_native_prefs", Context.MODE_PRIVATE) }

    // State
    var subs by remember {
        mutableStateOf(loadStoredSubscriptions(prefs))
    }
    var activeSubId by remember {
        mutableStateOf(prefs.getString("active_sub_id", subs.firstOrNull()?.id ?: "") ?: "")
    }
    var selectedServerId by remember {
        mutableStateOf(prefs.getString("selected_server_id", "") ?: "")
    }
    var pingMode by remember {
        mutableStateOf(prefs.getString("ping_mode", "tcp") ?: "tcp")
    }
    var searchQuery by remember { mutableStateOf("") }
    var filterProtocol by remember { mutableStateOf("all") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pingResults by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var isPinging by remember { mutableStateOf(false) }

    // Current sub and server
    val activeSub = subs.find { it.id == activeSubId } ?: subs.firstOrNull()
    val allServers = activeSub?.servers ?: emptyList()

    val filteredServers = allServers.filter { s ->
        val matchesQuery = searchQuery.isBlank() || s.name.contains(searchQuery, ignoreCase = true) || s.address.contains(searchQuery, ignoreCase = true)
        val matchesProto = filterProtocol == "all" || s.protocol.equals(filterProtocol, ignoreCase = true)
        val matchesFav = !favoritesOnly || s.favorite
        matchesQuery && matchesProto && matchesFav
    }

    val selectedServer = allServers.find { it.id == selectedServerId } ?: allServers.firstOrNull()

    // Save active state
    LaunchedEffect(activeSubId) {
        prefs.edit().putString("active_sub_id", activeSubId).apply()
    }
    LaunchedEffect(selectedServerId) {
        prefs.edit().putString("selected_server_id", selectedServerId).apply()
    }

    // Ping implementation
    fun pingAllServers() {
        if (isPinging) return
        isPinging = true
        scope.launch(Dispatchers.IO) {
            val results = mutableMapOf<String, Long?>()
            allServers.forEach { s ->
                val latency = try {
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    socket.connect(InetSocketAddress(s.address, s.port), 2500)
                    val elapsed = System.currentTimeMillis() - start
                    socket.close()
                    elapsed
                } catch (_: Throwable) {
                    null
                }
                results[s.id] = latency
            }
            withContext(Dispatchers.Main) {
                pingResults = results
                isPinging = false
            }
        }
    }

    Scaffold(
        containerColor = WaylineBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(WaylinePrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Logo",
                            tint = WaylinePrimaryGlow,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "WAYLINE",
                        color = WaylineTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WaylineSurfaceElevated)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v0.2",
                            color = WaylineTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Engine indicator pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(WaylineSurface)
                            .border(1.dp, WaylineBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (connected) WaylineSuccess else WaylineTextSecondary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "sing-box",
                                color = WaylineTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = WaylineTextSecondary
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Subscription Tabs bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(subs) { subItem ->
                    val isSelected = subItem.id == activeSubId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) WaylineSurfaceElevated else WaylineSurface)
                            .border(
                                1.dp,
                                if (isSelected) WaylinePrimary else WaylineBorder,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { activeSubId = subItem.id }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isSelected) "◆ " else "◇ ",
                                color = if (isSelected) WaylinePrimaryGlow else WaylineTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = subItem.name,
                                color = if (isSelected) WaylineTextPrimary else WaylineTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${subItem.servers.size}",
                                color = WaylineTextSecondary.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                item {
                    IconButton(
                        onClick = { showImport = !showImport },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showImport) WaylinePrimary else WaylineSurface)
                            .border(1.dp, WaylineBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Добавить",
                            tint = if (showImport) Color.White else WaylineTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Inline Add / Import Form
            if (showImport) {
                var newName by remember { mutableStateOf("") }
                var importText by remember { mutableStateOf("") }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = WaylineSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WaylineBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("Название подписки (определится автоматически)", fontSize = 13.sp, color = WaylineTextSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaylinePrimary,
                                unfocusedBorderColor = WaylineBorder,
                                focusedTextColor = WaylineTextPrimary,
                                unfocusedTextColor = WaylineTextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            placeholder = { Text("Ссылка HTTPS-подписки, VLESS / HY2 / Trojan / VMess / SS", fontSize = 13.sp, color = WaylineTextSecondary) },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaylinePrimary,
                                unfocusedBorderColor = WaylineBorder,
                                focusedTextColor = WaylineTextPrimary,
                                unfocusedTextColor = WaylineTextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val parsed = ConfigParser.parseConfigurations(importText)
                                    if (parsed.isNotEmpty()) {
                                        val newSub = Subscription(
                                            id = java.util.UUID.randomUUID().toString(),
                                            name = newName.ifBlank { "Подписка ${subs.size + 1}" },
                                            updated = "Только что",
                                            servers = parsed,
                                            sourceUrl = if (importText.startsWith("http")) importText.trim() else null
                                        )
                                        subs = subs + newSub
                                        activeSubId = newSub.id
                                        saveSubscriptions(prefs, subs)
                                        showImport = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = WaylinePrimary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Импортировать", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        importText = clip.getItemAt(0).text?.toString() ?: ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, WaylineBorder),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp), tint = WaylineTextPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Из буфера", fontSize = 13.sp, color = WaylineTextPrimary)
                            }
                        }
                    }
                }
            }

            // Active Subscription Card (NO THREE DOTS)
            if (activeSub != null && activeSub.servers.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = WaylineSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WaylineBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "АКТИВНАЯ ПОДПИСКА",
                                color = WaylineTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeSub.name,
                                color = WaylineTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${activeSub.updated} · ${activeSub.servers.size} серверов",
                                color = WaylineTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { pingAllServers() },
                                modifier = Modifier.size(36.dp),
                                enabled = !isPinging
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Проверить пинг",
                                    tint = if (isPinging) WaylineTextSecondary else WaylinePrimaryGlow,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { /* Refresh subscription if URL present */ },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Обновить",
                                    tint = WaylineTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Server Search and Filter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск серверов", fontSize = 13.sp, color = WaylineTextSecondary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WaylineTextSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = WaylineTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WaylinePrimary,
                        unfocusedBorderColor = WaylineBorder,
                        focusedTextColor = WaylineTextPrimary,
                        unfocusedTextColor = WaylineTextPrimary
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { favoritesOnly = !favoritesOnly },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (favoritesOnly) WaylinePrimary.copy(alpha = 0.2f) else WaylineSurface)
                        .border(1.dp, if (favoritesOnly) WaylinePrimary else WaylineBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = if (favoritesOnly) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Избранные",
                        tint = if (favoritesOnly) WaylineDanger else WaylineTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Server List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredServers) { s ->
                    val isSelected = s.id == (selectedServer?.id ?: "")
                    val ping = pingResults[s.id]

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) WaylineSurfaceElevated else WaylineSurface)
                            .border(
                                1.dp,
                                if (isSelected) WaylinePrimary else WaylineBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                if (!connected && !connecting) {
                                    selectedServerId = s.id
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ConfigParser.getCountryEmoji(s.flag),
                            fontSize = 22.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.name,
                                color = WaylineTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${s.protocol.uppercase()} · ${s.transport}",
                                color = WaylineTextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        // Ping badge
                        if (ping != null) {
                            Text(
                                text = "$ping ms",
                                color = if (ping < 100) WaylineSuccess else if (ping < 300) WaylineWarning else WaylineDanger,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        // Selection radio
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (isSelected) WaylinePrimary else WaylineBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(WaylinePrimaryGlow)
                                )
                            }
                        }
                    }
                }
            }

            // Power Connect Button Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing Halo Animation when connected or connecting
                val infiniteTransition = rememberInfiniteTransition(label = "halo")
                val haloScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (connected || connecting) 1.25f else 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )
                val haloAlpha by infiniteTransition.animateFloat(
                    initialValue = if (connected) 0.5f else 0.15f,
                    targetValue = if (connected) 0.15f else 0.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )

                // Halo circle
                Box(
                    modifier = Modifier
                        .size((110 * haloScale).dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    if (connected) WaylineSuccess.copy(alpha = haloAlpha) else WaylinePrimary.copy(alpha = haloAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Main Connect Button
                Button(
                    onClick = {
                        if (connected || connecting) {
                            onDisconnect()
                        } else {
                            val targetServer = selectedServer
                            if (targetServer != null) {
                                val singBoxJson = buildSingBoxConfig(targetServer)
                                onConnect(singBoxJson, "tun")
                            }
                        }
                    },
                    modifier = Modifier
                        .size(86.dp)
                        .shadow(12.dp, CircleShape),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) WaylineSuccess else WaylinePrimary
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Подключить",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (connecting) "..." else if (connected) "СТОП" else "ПУСК",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Connection state text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (connecting) WaylineWarning else if (connected) WaylineSuccess else WaylineTextSecondary
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (connecting) "Запуск ядра sing-box…" else if (connected) "Защищено · TUN активен" else "Отключено",
                    color = WaylineTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // Settings Modal Sheet
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = WaylineSurfaceElevated,
            contentColor = WaylineTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Настройки",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = WaylineTextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Ping Mode setting
                Text(
                    text = "Режим измерения пинга",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WaylineTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                listOf("tcp" to "TCP Connect (надёжный)", "icmp" to "ICMP Echo", "proxy_get" to "Proxy HTTP GET", "proxy_head" to "Proxy HTTP HEAD").forEach { (modeKey, modeTitle) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pingMode = modeKey
                                prefs.edit().putString("ping_mode", modeKey).apply()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = pingMode == modeKey,
                            onClick = {
                                pingMode = modeKey
                                prefs.edit().putString("ping_mode", modeKey).apply()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = WaylinePrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = modeTitle, fontSize = 14.sp, color = WaylineTextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = WaylineBorder)
                Spacer(modifier = Modifier.height(16.dp))

                // Sticky actions in Settings with confirmation
                var showResetConfirm by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, WaylineDanger),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Сбросить", color = WaylineDanger, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { showSettings = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = WaylinePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Готово", fontSize = 13.sp)
                    }
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Сброс настроек") },
                        text = { Text("Вы действительно хотите сбросить настройки к значениям по умолчанию?") },
                        confirmButton = {
                            TextButton(onClick = {
                                pingMode = "tcp"
                                prefs.edit().clear().apply()
                                showResetConfirm = false
                            }) {
                                Text("Да, сбросить", color = WaylineDanger)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Отмена")
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun loadStoredSubscriptions(prefs: android.content.SharedPreferences): List<Subscription> {
    val jsonStr = prefs.getString("saved_subscriptions", null) ?: return listOf(
        Subscription(
            id = "default_local",
            name = "Локальные профили",
            updated = "Только что",
            servers = emptyList()
        )
    )
    return try {
        val arr = org.json.JSONArray(jsonStr)
        val list = mutableListOf<Subscription>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null) list.add(Subscription.fromJson(obj))
        }
        if (list.isEmpty()) listOf(Subscription(id = "default_local", name = "Локальные профили", updated = "Только что")) else list
    } catch (_: Throwable) {
        listOf(Subscription(id = "default_local", name = "Локальные профили", updated = "Только что"))
    }
}

private fun saveSubscriptions(prefs: android.content.SharedPreferences, list: List<Subscription>) {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(it.toJson()) }
    prefs.edit().putString("saved_subscriptions", arr.toString()).apply()
}

private fun buildSingBoxConfig(server: Server): String {
    return """
    {
      "log": { "level": "warn" },
      "inbounds": [
        {
          "type": "tun",
          "tag": "tun-in",
          "interface_name": "wayline-tun",
          "inet4_address": "172.19.0.1/30",
          "auto_route": true,
          "strict_route": true,
          "stack": "system",
          "sniff": true
        }
      ],
      "outbounds": [
        {
          "type": "${server.protocol}",
          "tag": "proxy",
          "server": "${server.address}",
          "server_port": ${server.port}
        },
        { "type": "direct", "tag": "direct" },
        { "type": "dns", "tag": "dns-out" }
      ],
      "route": {
        "auto_detect_interface": true,
        "final": "proxy"
      }
    }
    """.trimIndent()
}
