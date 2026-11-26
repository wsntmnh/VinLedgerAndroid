package com.shidongliang.vinledger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

private val AccentGreen = Color(0xFF4CAF50)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    val refresh: () -> Unit = { tick += 1 }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "没有相机权限，扫码用不了，请在系统设置里给本应用开权限", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val status = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (status != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF141414)) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = "扫码") },
                    label = { Text("扫码") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = "记录") },
                    label = { Text("记录") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.IosShare, contentDescription = "导出") },
                    label = { Text("导出") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ScanScreen(onChanged = refresh)
                1 -> HistoryScreen(tick = tick, onChanged = refresh)
                else -> ExportScreen(tick = tick, onChanged = refresh)
            }
        }
    }
}

// MARK: - 扫码页

private data class PendingScan(val recordId: String, val content: String)

@Composable
fun ScanScreen(onChanged: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingScan?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(scanEnabled = scanning && pending == null, torchOn = torch) { raw, format ->
            if (!scanning || pending != null) return@CameraPreview
            val normalized = VinUtil.vinFrom(raw) ?: raw
            if (Store.hasRecent(normalized, 5)) {
                copyToClipboard(context, normalized)
                Toast.makeText(context, "刚记录过，未重复添加（已复制）", Toast.LENGTH_SHORT).show()
                return@CameraPreview
            }
            val record = Store.add(normalized, "相机", format, null)
            scanning = false
            pending = PendingScan(record.id, normalized)
            onChanged()
        }

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "把条形码 / 车架号放进框内",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 20.dp)
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(width = 260.dp, height = 150.dp)
                    .border(2.dp, AccentGreen, RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.weight(1f))
            TorchButton(torch) { torch = !torch }
            Spacer(Modifier.height(28.dp))
        }

        val current = pending
        if (current != null) {
            AlertDialog(
                onDismissRequest = {
                    pending = null
                    scanning = true
                },
                title = {
                    Text(if (VinUtil.vinFrom(current.content) != null) "识别到车架号" else "识别到扫码内容")
                },
                text = {
                    Text(current.content, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            finishPending(current, Direction.IN, context) {
                                pending = null
                                scanning = true
                                onChanged()
                            }
                        }) { Text("入库") }
                        TextButton(onClick = {
                            finishPending(current, Direction.OUT, context) {
                                pending = null
                                scanning = true
                                onChanged()
                            }
                        }) { Text("出库") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pending = null
                        scanning = true
                    }) { Text("跳过") }
                }
            )
        }
    }
}

private fun finishPending(
    pending: PendingScan,
    direction: Direction?,
    context: Context,
    done: () -> Unit
) {
    if (direction == null) {
        Toast.makeText(context, "已跳过，未记录方向", Toast.LENGTH_SHORT).show()
        done()
        return
    }
    if (Store.hasSame(pending.content, direction, pending.recordId)) {
        Store.remove(pending.recordId)
        copyToClipboard(context, pending.content)
        Toast.makeText(context, "该车架号已有${direction.label}记录，未重复添加（已复制）", Toast.LENGTH_SHORT).show()
    } else {
        Store.updateDirection(pending.recordId, direction)
        Toast.makeText(context, "已记为${direction.label}", Toast.LENGTH_SHORT).show()
    }
    done()
}

@Composable
private fun TorchButton(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(62.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(31.dp))
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (on) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "手电筒",
                tint = if (on) Color(0xFFFFEB3B) else Color.White
            )
            Text(if (on) "关闭" else "手电筒", color = Color.White, fontSize = 11.sp)
        }
    }
}

// MARK: - 记录页

@Composable
fun HistoryScreen(tick: Int, onChanged: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(tick, query) {
        if (query.isBlank()) {
            Store.records.toList()
        } else {
            Store.records.filter { r ->
                r.content.contains(query, true) ||
                    r.timeText.contains(query) ||
                    (Store.brandFor(r.content) ?: "").contains(query, true) ||
                    r.symbology.contains(query, true)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索车架号 / 品牌 / 时间") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("共 ${list.size} 条（最多保留 500 条）", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Text("暂无记录", color = Color.Gray, modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { record ->
                    RecordRow(record) {
                        Store.remove(record.id)
                        onChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(record: ScanRecord, onDelete: () -> Unit) {
    Surface(
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.content, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                    val direction = record.direction
                    if (direction != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            direction.label,
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (direction == Direction.IN) Color(0xFF2E7D32) else Color(0xFFEF6C00))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    val brand = Store.brandFor(record.content)
                    if (brand != null) {
                        Text(brand, fontSize = 11.sp, color = Color(0xFF81C784))
                        Text("  ·  ", fontSize = 11.sp, color = Color.Gray)
                    }
                    Text(record.timeText, fontSize = 11.sp, color = Color.Gray)
                    Text("  ·  ", fontSize = 11.sp, color = Color.Gray)
                    Text(record.symbology, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFF888888))
            }
        }
    }
}

// MARK: - 导出页

@Composable
fun ExportScreen(tick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var range by remember { mutableStateOf(ExportRange.ALL) }
    var dirFilter by remember { mutableStateOf(DirFilter.ALL) }
    var vinOnly by remember { mutableStateOf(true) }
    var prefix by remember { mutableStateOf("全部") }
    var keyword by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ExportFormat.TXT) }
    var options by remember { mutableStateOf(ExportOptions()) }
    var newPrefix by remember { mutableStateOf("") }
    var newBrand by remember { mutableStateOf("") }

    val prefixes = remember(tick) {
        Store.records.mapNotNull { VinUtil.prefixOf(it.content) }.distinct().sorted()
    }
    val filtered = remember(tick, range, dirFilter, vinOnly, prefix, keyword) {
        filterRecords(Store.records.toList(), range, dirFilter, vinOnly, prefix, keyword)
    }
    val content = remember(filtered, format, options) { buildExport(filtered, format, options) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrEmpty()) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
            } else {
                val added = importCsv(text)
                onChanged()
                Toast.makeText(
                    context,
                    if (added > 0) "已导入 $added 条记录" else "没有新增（都已在历史里）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle("时间范围")
        ChipRow {
            ExportRange.values().forEach { item ->
                ChoiceChip(item.label, range == item) { range = item }
            }
        }

        SectionTitle("入库 / 出库")
        ChipRow {
            DirFilter.values().forEach { item ->
                ChoiceChip(item.label, dirFilter == item) { dirFilter = item }
            }
        }

        SectionTitle("车架号")
        SwitchRow("只导出完整车架号", vinOnly) { vinOnly = it }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("全部", prefix == "全部") { prefix = "全部" }
            prefixes.forEach { p ->
                val brand = Store.brands[p] ?: BrandBook.builtin[p] ?: BrandBook.builtin[p.take(3)]
                ChoiceChip(if (brand != null) "$p·$brand" else p, prefix == p) { prefix = p }
            }
        }
        if (prefixes.isEmpty()) {
            Text("还没有车架号记录", fontSize = 12.sp, color = Color.Gray)
        }

        SectionTitle("搜索")
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text("车架号 / 品牌 / 码制") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("导出项目")
        SwitchRow("品牌", options.brand) { options = options.copy(brand = it) }
        SwitchRow("入库 / 出库", options.direction) { options = options.copy(direction = it) }
        SwitchRow("扫描时间", options.time) { options = options.copy(time = it) }
        SwitchRow("来源", options.source) { options = options.copy(source = it) }
        SwitchRow("码制", options.symbology) { options = options.copy(symbology = it) }

        Divider(color = Color(0xFF2A2A2A))
        SectionTitle("品牌前缀（可手动添加）")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newPrefix,
                onValueChange = { newPrefix = it.uppercase(Locale.US).take(5) },
                label = { Text("前 5 位") },
                singleLine = true,
                modifier = Modifier.width(110.dp)
            )
            OutlinedTextField(
                value = newBrand,
                onValueChange = { newBrand = it },
                label = { Text("品牌名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (newPrefix.trim().length >= 3 && newBrand.trim().isNotEmpty()) {
                    Store.addBrand(newPrefix, newBrand)
                    newPrefix = ""
                    newBrand = ""
                    onChanged()
                } else {
                    Toast.makeText(context, "前缀至少 3 位，品牌名不能为空", Toast.LENGTH_SHORT).show()
                }
            }) { Text("添加") }
        }
        Store.brands.forEach { (key, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$key  $value", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                IconButton(onClick = {
                    Store.removeBrand(key)
                    onChanged()
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFF888888))
                }
            }
        }

        Divider(color = Color(0xFF2A2A2A))
        SectionTitle("文件格式")
        ChipRow {
            ExportFormat.values().forEach { item ->
                ChoiceChip(item.label, format == item) { format = item }
            }
        }

        Text("可导出 ${filtered.size} 条（共 ${Store.records.size} 条）", fontSize = 13.sp, color = Color.Gray)

        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    content.ifEmpty { "没有符合条件的记录" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFDDDDDD)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (content.isEmpty()) {
                        Toast.makeText(context, "没有可导出的内容", Toast.LENGTH_SHORT).show()
                    } else {
                        copyToClipboard(context, content)
                        Toast.makeText(context, "已复制 ${filtered.size} 条到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制") }

            Button(
                onClick = {
                    if (content.isEmpty()) {
                        Toast.makeText(context, "没有可导出的内容", Toast.LENGTH_SHORT).show()
                    } else {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
                        shareText(context, content, "车架号台账-$stamp.${format.ext}")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("导出文件") }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/*", "application/csv", "text/comma-separated-values")) },
                modifier = Modifier.weight(1f)
            ) { Text("导入 CSV") }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9E9E9E))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AccentGreen else Color(0xFF2A2A2A))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
