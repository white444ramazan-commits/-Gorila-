package com.gorilla.companion

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val GAME_PACKAGE = "com.pubg.krmobile"
private const val MODULE_PATH = "/data/adb/modules/gorilla_pubg_companion"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GorillaApp() }
    }
}
private enum class Page(val label: String) {
    HOME("HOME"), AIM("AIM"), ESP("ESP"), SETTINGS("SETTINGS"), LOGS("LOGS")
}
private data class RootStatus(
    val root: Boolean = false, val moduleInstalled: Boolean = false,
    val moduleEnabled: Boolean = false, val details: String = "Checking KernelSU…"
)
private data class PerformanceSnapshot(
    val cpuPercent: Int? = null, val gpuTemperatureC: Float? = null,
    val thermalStatus: String = "Unknown", val refreshRate: Float = 0f
)

private object RootBridge {
    private fun su(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else process.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (_: Exception) { null }

    fun readStatus(): RootStatus {
        val output = su(
            "uid=\$(id -u 2>/dev/null); installed=0; enabled=0; " +
                "[ -d $MODULE_PATH ] && installed=1; " +
                "if [ \"\$installed\" = 1 ] && [ ! -f $MODULE_PATH/disable ] && [ ! -f $MODULE_PATH/remove ]; then enabled=1; fi; " +
                "echo ROOT=\$uid; echo INSTALLED=\$installed; echo ENABLED=\$enabled"
        ) ?: return RootStatus(details = "Root permission not granted or KernelSU su unavailable")
        val lines = output.lines().associate {
            val pair = it.split("=", limit = 2)
            pair.getOrElse(0) { "" } to pair.getOrElse(1) { "" }
        }
        val root = lines["ROOT"] == "0"
        val installed = lines["INSTALLED"] == "1"
        val enabled = lines["ENABLED"] == "1"
        val detail = when {
            !root -> "Root shell did not return UID 0"
            !installed -> "KernelSU module is not installed"
            !enabled -> "Module is installed but disabled"
            else -> "Root and module detected"
        }
        return RootStatus(root, installed, enabled, detail)
    }
    fun readLogs(): String = su("cat $MODULE_PATH/logs/boot.log 2>/dev/null")
        ?.takeIf { it.isNotBlank() } ?: "No module log available yet. Install the ZIP and reboot once."
}

private object PerformanceMonitor {
    private var previousCpu: LongArray? = null
    @Synchronized fun read(context: Context): PerformanceSnapshot {
        val cpu = readCpuLoad()
        val gpu = readGpuTemperature()
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            when (power.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> "Normal"
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                else -> "Unknown"
            }
        } else "Unavailable on this Android version"
        val refresh = try {
            val activity = context as? Activity
            if (Build.VERSION.SDK_INT >= 30) activity?.display?.refreshRate ?: 0f
            else {
                @Suppress("DEPRECATION")
                activity?.windowManager?.defaultDisplay?.refreshRate ?: 0f
            }
        } catch (_: Exception) { 0f }
        return PerformanceSnapshot(cpu, gpu, thermal, refresh)
    }
    private fun readCpuLoad(): Int? = try {
        val values = File("/proc/stat").useLines { lines ->
            val fields = lines.first().trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            longArrayOf(fields.sum(), fields[3] + fields[4])
        }
        val old = previousCpu
        previousCpu = values
        if (old == null) null else {
            val total = values[0] - old[0]
            val idle = values[1] - old[1]
            if (total <= 0) null else (((total - idle).toDouble() / total) * 100).toInt().coerceIn(0, 100)
        }
    } catch (_: Exception) { null }

    private fun readGpuTemperature(): Float? = try {
        val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }.orEmpty()
        zones.firstNotNullOfOrNull { zone ->
            val type = File(zone, "type").takeIf { it.canRead() }?.readText()?.trim().orEmpty()
            if (!type.contains("gpu", true)) return@firstNotNullOfOrNull null
            val raw = File(zone, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toFloatOrNull()
                ?: return@firstNotNullOfOrNull null
            if (raw > 1000f) raw / 1000f else raw
        }
    } catch (_: Exception) { null }
}

@Composable
private fun GorillaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gorilla_settings", Context.MODE_PRIVATE) }
    var page by remember { mutableStateOf(Page.HOME) }
    var root by remember { mutableStateOf(RootStatus()) }
    var game by remember { mutableStateOf("Checking package…") }
    var logs by remember { mutableStateOf("Loading module log…") }
    var perf by remember { mutableStateOf(PerformanceSnapshot()) }
    val fps = remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frames = 0
        var start = System.nanoTime()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames++
                if (frameTimeNanos - start >= 1_000_000_000L) {
                    fps.intValue = frames
                    frames = 0
                    start = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }
    LaunchedEffect(Unit) {
        root = withContext(Dispatchers.IO) { RootBridge.readStatus() }
        game = withContext(Dispatchers.IO) { readGameStatus(context) }
        while (true) {
            perf = withContext(Dispatchers.IO) { PerformanceMonitor.read(context) }
            delay(2000)
        }
    }
    LaunchedEffect(page) {
        if (page == Page.LOGS) logs = withContext(Dispatchers.IO) { RootBridge.readLogs() }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF8B7CFF), secondary = Color(0xFF33D6C5),
        background = Color(0xFF090B12), surface = Color(0xFF151927)
    )) {
        Column(Modifier.fillMaxSize().background(Color(0xFF090B12)).padding(14.dp)) {
            Header(fps.intValue, root)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Page.values().forEach { item ->
                    FilterChip(selected = page == item, onClick = { page = item },
                        label = { Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (page) {
                    Page.HOME -> HomeScreen(root, game, perf) { page = Page.ESP }
                    Page.AIM -> AimScreen(prefs)
                    Page.ESP -> EspScreen(prefs)
                    Page.SETTINGS -> SettingsScreen(perf)
                    Page.LOGS -> LogsScreen(logs)
                }
            }
        }
    }
}

@Composable private fun Header(fps: Int, status: RootStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2B)),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("GORILLA", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("KERNELSU COMPANION", color = Color(0xFF9EA8C5), fontSize = 10.sp, letterSpacing = 2.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$fps FPS", color = Color(0xFF41D9C5), fontWeight = FontWeight.Bold)
                Text(if (status.root && status.moduleEnabled) "ROOT READY" else "CHECK STATUS",
                    color = if (status.root && status.moduleEnabled) Color(0xFF62D99A) else Color(0xFFFFC36B),
                    fontSize = 10.sp)
            }
        }
    }
}
@Composable private fun Panel(title: String, body: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 11.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151927)),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = Color(0xFFB5AAFF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            body()
        }
    }
}
@Composable private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF9EA8C5), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun HomeScreen(root: RootStatus, game: String, perf: PerformanceSnapshot, onStart: () -> Unit) {
    Panel("SYSTEM STATUS") {
        StatusLine("Root access", if (root.root) "Granted" else "Unavailable")
        StatusLine("KernelSU module", when {
            !root.moduleInstalled -> "Not installed"
            root.moduleEnabled -> "Enabled"
            else -> "Disabled"
        })
        Text(root.details, color = Color(0xFF9EA8C5), fontSize = 11.sp)
    }
    Panel("PUBG MOBILE KR — DETECTION ONLY") {
        Text(game, color = Color.White, fontSize = 13.sp)
        Text("Package/version detection does not verify gameplay compatibility. No game process is opened or modified.",
            color = Color(0xFF9EA8C5), fontSize = 11.sp)
    }
    Panel("LIVE DEVICE METRICS") {
        StatusLine("CPU load", perf.cpuPercent?.let { "$it%" } ?: "Unavailable")
        StatusLine("GPU temperature", perf.gpuTemperatureC?.let { "%.1f °C".format(it) } ?: "Sensor unavailable")
        StatusLine("Thermal state", perf.thermalStatus)
        StatusLine("Display refresh", if (perf.refreshRate > 0f) "%.0f Hz".format(perf.refreshRate) else "Unknown")
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7767F5)),
        shape = RoundedCornerShape(14.dp)) { Text("START TEST SCENE", fontWeight = FontWeight.Bold) }
    Text("START opens the companion's local demo scene. It does not launch or alter PUBG.",
        color = Color(0xFF8E98B2), fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun AimScreen(prefs: android.content.SharedPreferences) {
    var enabled by remember { mutableStateOf(prefs.getBoolean("aim_enabled", false)) }
    var sensitivity by remember { mutableStateOf(prefs.getFloat("aim_sensitivity", 1f)) }
    var speed by remember { mutableStateOf(prefs.getFloat("aim_speed", 1f)) }
    var smoothing by remember { mutableStateOf(prefs.getFloat("aim_smoothing", .65f)) }
    var profile by remember { mutableStateOf(prefs.getString("aim_profile", "Balanced") ?: "Balanced") }
    LaunchedEffect(enabled, sensitivity, speed, smoothing, profile) {
        prefs.edit().putBoolean("aim_enabled", enabled).putFloat("aim_sensitivity", sensitivity)
            .putFloat("aim_speed", speed).putFloat("aim_smoothing", smoothing).putString("aim_profile", profile).apply()
    }
    Panel("AIM — MANUAL CONTROL PROFILE") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Enable saved profile", color = Color.White, fontWeight = FontWeight.Medium)
                Text("Settings only; no automated aiming or touch injection.", color = Color(0xFF9EA8C5), fontSize = 10.sp)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
    }
    Panel("CONTROL PRESETS") {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Balanced", "Precision", "Fast").forEach { preset ->
                FilterChip(selected = profile == preset, onClick = {
                    profile = preset
                    when (preset) {
                        "Precision" -> { sensitivity = .75f; speed = .75f; smoothing = .85f }
                        "Fast" -> { sensitivity = 1.45f; speed = 1.4f; smoothing = .45f }
                        else -> { sensitivity = 1f; speed = 1f; smoothing = .65f }
                    }
                }, label = { Text(preset, fontSize = 11.sp) })
            }
        }
    }
    Panel("SENSITIVITY · %.2f".format(sensitivity)) {
        Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = .25f..2f)
    }
    Panel("RESPONSE SPEED · %.2f".format(speed)) {
        Slider(value = speed, onValueChange = { speed = it }, valueRange = .25f..2f)
    }
    Panel("SMOOTHING · %.2f".format(smoothing)) {
        Slider(value = smoothing, onValueChange = { smoothing = it }, valueRange = .05f..1f)
    }
    val preview = if (NativeBridge.available)
        runCatching { NativeBridge.nativeResponsePreview(.5f, sensitivity, smoothing) }.getOrDefault(0f)
    else 0f
    Panel("LOCAL RESPONSE-CURVE PREVIEW") {
        StatusLine("Sample input delta", "0.50")
        StatusLine("Preview output", "%.3f".format(preview))
        Text(if (NativeBridge.available) NativeBridge.nativeBuildInfo()
            else "Native library unavailable; Kotlin UI settings remain usable.",
            color = Color(0xFF8E98B2), fontSize = 10.sp)
    }
}
@Composable private fun EspScreen(prefs: android.content.SharedPreferences) {
    var boxes by remember { mutableStateOf(prefs.getBoolean("esp_boxes", true)) }
    var distance by remember { mutableStateOf(prefs.getBoolean("esp_distance", true)) }
    var coordinates by remember { mutableStateOf(prefs.getBoolean("esp_coordinates", true)) }
    var skeleton by remember { mutableStateOf(prefs.getBoolean("esp_skeleton", true)) }
    LaunchedEffect(boxes, distance, coordinates, skeleton) {
        prefs.edit().putBoolean("esp_boxes", boxes).putBoolean("esp_distance", distance)
            .putBoolean("esp_coordinates", coordinates).putBoolean("esp_skeleton", skeleton).apply()
    }
    Panel("ESP — SYNTHETIC TEST SCENE") {
        Text("All entities are generated sample data rendered inside this companion app. No PUBG entities, memory, or external processes are read.",
            color = Color(0xFF9EA8C5), fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        TestScene(boxes, distance, coordinates, skeleton)
    }
    Panel("VISUAL ELEMENTS") {
        SettingSwitch("Bounding boxes", boxes) { boxes = it }
        SettingSwitch("Distance labels", distance) { distance = it }
        SettingSwitch("World coordinates", coordinates) { coordinates = it }
        SettingSwitch("Skeleton", skeleton) { skeleton = it }
    }
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
private data class DemoEntity(val x: Float, val y: Float, val distance: Int, val wx: Float, val wy: Float)
@Composable private fun TestScene(boxes: Boolean, distance: Boolean, coordinates: Boolean, skeleton: Boolean) {
    val entities = listOf(DemoEntity(.28f, .28f, 24, 123.4f, 88.2f), DemoEntity(.68f, .42f, 51, -42.1f, 16.8f))
    Canvas(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0C1720))) {
        drawRect(Color(0xFF112631))
        for (i in 1..5) {
            val y = size.height * i / 6f
            drawLine(Color(0x223CD0C0), androidx.compose.ui.geometry.Offset(0f, y),
                androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
        }
        entities.forEach { entity ->
            val cx = size.width * entity.x
            val top = size.height * entity.y
            val halfW = size.width * .13f
            val boxH = size.height * .42f
            if (boxes) drawRect(Color(0xFF44E0C6), androidx.compose.ui.geometry.Offset(cx - halfW, top),
                androidx.compose.ui.geometry.Size(halfW * 2f, boxH), style = Stroke(width = 2f))
            if (skeleton) {
                val head = androidx.compose.ui.geometry.Offset(cx, top + boxH * .13f)
                val neck = androidx.compose.ui.geometry.Offset(cx, top + boxH * .25f)
                val hip = androidx.compose.ui.geometry.Offset(cx, top + boxH * .58f)
                val ls = androidx.compose.ui.geometry.Offset(cx - halfW * .65f, top + boxH * .29f)
                val rs = androidx.compose.ui.geometry.Offset(cx + halfW * .65f, top + boxH * .29f)
                val lh = androidx.compose.ui.geometry.Offset(cx - halfW, top + boxH * .52f)
                val rh = androidx.compose.ui.geometry.Offset(cx + halfW, top + boxH * .52f)
                val lf = androidx.compose.ui.geometry.Offset(cx - halfW * .55f, top + boxH)
                val rf = androidx.compose.ui.geometry.Offset(cx + halfW * .55f, top + boxH)
                listOf(head to neck, neck to hip, neck to ls, neck to rs, ls to lh, rs to rh, hip to lf, hip to rf)
                    .forEach { (a, b) -> drawLine(Color(0xFFFFD166), a, b, strokeWidth = 2.2f) }
                drawCircle(Color(0xFFFFD166), 7f, head, style = Stroke(width = 2f))
            }
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 27f; isAntiAlias = true }
            if (distance) drawContext.canvas.nativeCanvas.drawText("${entity.distance}m", cx - halfW, top - 7f, paint)
            if (coordinates) {
                paint.textSize = 19f
                paint.color = android.graphics.Color.rgb(91, 218, 202)
                drawContext.canvas.nativeCanvas.drawText("X %.1f  Y %.1f".format(entity.wx, entity.wy),
                    cx - halfW, top + boxH + 25f, paint)
            }
        }
    }
    Text("DEMO DATA · 2 SAMPLE ENTITIES", color = Color(0xFF61D9C8), fontSize = 9.sp,
        modifier = Modifier.padding(top = 7.dp))
}
@Composable private fun SettingsScreen(perf: PerformanceSnapshot) {
    val context = LocalContext.current
    Panel("DEVICE INFORMATION") {
        StatusLine("Manufacturer", Build.MANUFACTURER)
        StatusLine("Model", Build.MODEL)
        StatusLine("Android", Build.VERSION.RELEASE)
        StatusLine("API level", Build.VERSION.SDK_INT.toString())
        StatusLine("Primary ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
    }
    Panel("PERFORMANCE MONITOR") {
        StatusLine("Companion UI FPS", "Measured in app header")
        StatusLine("CPU load", perf.cpuPercent?.let { "$it%" } ?: "Unavailable")
        StatusLine("GPU temperature", perf.gpuTemperatureC?.let { "%.1f °C".format(it) } ?: "Sensor unavailable")
        StatusLine("Thermal status", perf.thermalStatus)
        StatusLine("Current refresh", if (perf.refreshRate > 0f) "%.0f Hz".format(perf.refreshRate) else "Unknown")
    }
    Panel("DISPLAY REFRESH") {
        Button(onClick = {
            val activity = context as? Activity ?: return@Button
            try {
                @Suppress("DEPRECATION")
                val modes = activity.windowManager.defaultDisplay.supportedModes
                val best = modes.maxByOrNull { it.refreshRate } ?: return@Button
                activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.modeId }
            } catch (_: Exception) { /* Non-fatal if unsupported. */ }
        }, modifier = Modifier.fillMaxWidth()) { Text("REQUEST HIGHEST SUPPORTED REFRESH") }
        Text("This request affects only the companion app window. CPU/GPU readings depend on device sensors. No governor, thermal limit, or global system property is changed.",
            color = Color(0xFF9EA8C5), fontSize = 10.sp)
    }
}
@Composable private fun LogsScreen(logs: String) {
    Panel("KERNELSU MODULE LOG") {
        Text(logs, color = Color(0xFFD7DCEF), fontSize = 10.sp, lineHeight = 15.sp)
        Text("Log is read-only. Refresh by reopening this tab.", color = Color(0xFF8E98B2), fontSize = 10.sp)
    }
}
private fun readGameStatus(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= 33)
        context.packageManager.getPackageInfo(GAME_PACKAGE, PackageManager.PackageInfoFlags.of(0))
    else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(GAME_PACKAGE, 0)
    }
    val version = info.versionName ?: "version name unavailable"
    if (version.contains("4.7", true))
        "Package found · $version · version string contains 4.7 (not a compatibility certification)"
    else "Package found · $version · PUBG KR 4.7 was not verified"
} catch (_: PackageManager.NameNotFoundException) {
    "Package $GAME_PACKAGE not installed"
} catch (e: Exception) {
    "Package check unavailable: ${e.javaClass.simpleName}"
}
