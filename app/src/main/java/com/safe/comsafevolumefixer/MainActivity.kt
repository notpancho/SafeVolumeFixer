package com.safe.comsafevolumefixer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safe.comsafevolumefixer.ui.theme.ComSafeVolumeFixerTheme
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

enum class Screen {
    Status, HowItWorks, Game, Logs
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFix(this)
        startFixerService()

        setContent {
            var currentScreen by remember { mutableStateOf(Screen.Status) }

            ComSafeVolumeFixerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        Screen.Status -> StatusScreen(
                            onNavigateToHowItWorks = { currentScreen = Screen.HowItWorks },
                            onNavigateToLogs = { currentScreen = Screen.Logs },
                            onLaunchGame = { currentScreen = Screen.Game }
                        )
                        Screen.HowItWorks -> HowItWorksScreen(
                            onBack = { currentScreen = Screen.Status }
                        )
                        Screen.Logs -> LogsScreen(
                            onBack = { currentScreen = Screen.Status }
                        )
                        Screen.Game -> GameScreen(
                            onBack = { currentScreen = Screen.Status }
                        )
                    }
                }
            }
        }
    }

    private fun startFixerService() {
        val serviceIntent = Intent(this, FixerService::class.java)
        startForegroundService(serviceIntent)
    }

    companion object {
        fun applyFix(context: Context) {
            try {
                val resolver = context.contentResolver
                Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
                Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
                Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
                Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
                Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
                Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f)
                Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0)
                
                Logger.log(context, "Hardened fix applied (Manual)")
            } catch (e: SecurityException) {
                Logger.log(context, "ERROR: Permission missing on manual fix")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onNavigateToHowItWorks: () -> Unit, onNavigateToLogs: () -> Unit, onLaunchGame: () -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var tapCount by remember { mutableIntStateOf(0) }
    
    val isPermissionGranted = remember {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }
    val isServiceRunning = remember { isServiceRunning(context, FixerService::class.java) }
    val isIgnoringBatteryOptimizations = remember {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_title)) },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.History, contentDescription = "View Logs")
                    }
                    IconButton(onClick = onNavigateToHowItWorks) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "How it works")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(
                title = stringResource(R.string.status_permission),
                status = if (isPermissionGranted) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                isOk = isPermissionGranted,
                icon = if (isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning
            )

            Column {
                StatusCard(
                    title = stringResource(R.string.status_service),
                    status = if (isServiceRunning) stringResource(R.string.active) else stringResource(R.string.inactive),
                    isOk = isServiceRunning,
                    icon = if (isServiceRunning) Icons.Default.CheckCircle else Icons.Default.Info
                )
                Text(text = stringResource(R.string.service_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = stringResource(R.string.safety_disclaimer_title), style = MaterialTheme.typography.titleSmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = stringResource(R.string.safety_disclaimer_body), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                }
            }

            if (!isIgnoringBatteryOptimizations) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") })
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text(stringResource(R.string.btn_battery_optimization)) }
            }

            HorizontalDivider()
            Text(stringResource(R.string.adb_instruction_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.adb_instruction_body), style = MaterialTheme.typography.bodyMedium)

            val adbCommand = stringResource(R.string.adb_command)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    @Suppress("DEPRECATION")
                    Text(text = adbCommand, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { clipboardManager.setText(AnnotatedString(adbCommand)) }, modifier = Modifier.align(Alignment.End)) { Text("Copy Command") }
                }
            }
            Text(text = stringResource(R.string.adb_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.weight(1f))
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            Text(
                text = stringResource(R.string.version_label, packageInfo.versionName ?: "Unknown", packageInfo.longVersionCode.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        tapCount++
                        if (tapCount >= 5) {
                            tapCount = 0
                            onLaunchGame()
                        }
                    }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(Logger.getLogs(context)) }
    BackHandler { onBack() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { logs = Logger.getLogs(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { 
                        Logger.clearLogs(context)
                        logs = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_logs))
            }
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                logs.forEach { log ->
                    ListItem(
                        headlineContent = { Text(log, style = MaterialTheme.typography.bodyMedium) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = stringResource(R.string.about_body), style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    var score by remember { mutableIntStateOf(0) }
    var highVolumeLevel by remember { mutableFloatStateOf(0.5f) }
    var gameActive by remember { mutableStateOf(true) }
    var targetX by remember { mutableFloatStateOf(0.5f) }
    var targetY by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(gameActive) {
        while (gameActive) {
            delay(800.milliseconds)
            targetX = Random.nextFloat() * 0.7f + 0.1f
            targetY = Random.nextFloat() * 0.7f + 0.1f
        }
    }

    LaunchedEffect(gameActive) {
        while (gameActive) {
            delay(100.milliseconds)
            highVolumeLevel -= 0.015f
            if (highVolumeLevel <= 0) {
                gameActive = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Volume Defense!") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (gameActive) {
                Column(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("KEEP THE VOLUME UP!", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { highVolumeLevel }, modifier = Modifier.fillMaxWidth().height(20.dp).padding(vertical = 8.dp), color = if (highVolumeLevel > 0.3f) Color.Green else Color.Red)
                    Text("Score: $score", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .offset(x = (targetX * 260).dp, y = (targetY * 380).dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                score += 10
                                highVolumeLevel = (highVolumeLevel + 0.2f).coerceAtMost(1f)
                                targetX = Random.nextFloat() * 0.7f + 0.1f
                                targetY = Random.nextFloat() * 0.7f + 0.1f
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GAME OVER!", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                    Text("The system warning won...", fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Final Score: $score", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = { 
                        score = 0
                        highVolumeLevel = 0.5f
                        gameActive = true 
                    }) {
                        Text("TRY AGAIN")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(title: String, status: String, isOk: Boolean, icon: ImageVector) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isOk) (if (isDark) Color(0xFF1E3320) else Color(0xFFE8F5E9)) else (if (isDark) Color(0xFF332A1E) else Color(0xFFFFF3E0))
    val contentColor = if (isOk) (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)) else (if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00))

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.labelLarge, color = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f))
                Text(text = status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = contentColor)
            }
        }
    }
}

private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    for (service in manager.getRunningServices(Int.MAX_VALUE)) { if (serviceClass.name == service.service.className) return true }
    return false
}
