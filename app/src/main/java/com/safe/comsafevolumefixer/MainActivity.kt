package com.safe.comsafevolumefixer

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.safe.comsafevolumefixer.ui.theme.ComSafeVolumeFixerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

enum class Screen {
    Status, HowItWorks, Game, Logs
}

class MainActivity : ComponentActivity() {

    // Bumped on every onResume so the status screen re-reads permission / service / battery state
    // (e.g. after returning from the battery optimization dialog).
    private var resumeTick by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on a fresh launch - not on every rotation / theme change (which would spam the log).
        if (savedInstanceState == null) {
            applyFix(this, "Manual/App Open")
            requestNotificationPermissionIfNeeded()
        }
        startFixerService()

        setContent {
            var currentScreen by rememberSaveable { mutableStateOf(Screen.Status) }

            ComSafeVolumeFixerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        Screen.Status -> StatusScreen(
                            refreshKey = resumeTick,
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

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startFixerService() {
        try {
            startForegroundService(Intent(this, FixerService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("VolumeFixer", "Could not start FixerService: ${e.message}")
        }
    }

    companion object {
        fun applyFix(context: Context, source: String) {
            // All Settings writes run on Dispatchers.IO inside SafetySettings.
            SafetySettings.applyAsync(context, source)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    refreshKey: Int,
    onNavigateToHowItWorks: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onLaunchGame: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var tapCount by remember { mutableIntStateOf(0) }

    val isPermissionGranted = remember(refreshKey) {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }
    val isServiceRunning = remember(refreshKey) { isServiceRunning(context, FixerService::class.java) }
    val isIgnoringBatteryOptimizations = remember(refreshKey) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_title)) },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.History, contentDescription = stringResource(R.string.cd_view_logs))
                    }
                    IconButton(onClick = onNavigateToHowItWorks) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = stringResource(R.string.cd_how_it_works))
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
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:${context.packageName}".toUri() })
                        } catch (_: Exception) {
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
                    Text(text = adbCommand, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("adb command", adbCommand)))
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(stringResource(R.string.btn_copy_command)) }
                }
            }
            Text(text = stringResource(R.string.adb_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.version_label, packageInfo.versionName ?: stringResource(R.string.unknown_version), packageInfo.longVersionCode.toInt()),
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
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val shareChooserTitle = stringResource(R.string.share_logs_chooser)
    BackHandler { onBack() }

    // The log can hold thousands of events - read it off the main thread.
    LaunchedEffect(reloadKey) {
        logs = withContext(Dispatchers.IO) { Logger.getLogs(context) }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title_count, logs.size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (logs.isNotEmpty()) {
                            // Share the full log as a file (a text extra would exceed the binder size limit).
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { Logger.exportToFile(context) }
                                if (file != null) {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_export_logs))
                    }
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Logger.clearLogs(context) }
                            logs = emptyList()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear_logs))
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loaded) Text(stringResource(R.string.no_logs))
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(logs) { entry ->
                    LogItemCard(entry)
                }
            }
        }
    }
}

@Composable
fun LogItemCard(entry: LogEntry) {
    val isTrigger = entry.message.startsWith(">>> TRIGGER")
    val isAction = entry.message.startsWith("ACTION")
    val isError = entry.message.contains("ERROR") || entry.message.startsWith("CRASH")

    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isTrigger -> MaterialTheme.colorScheme.primaryContainer
        isAction -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isTrigger -> MaterialTheme.colorScheme.onPrimaryContainer
        isAction -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val icon = when {
        isError -> Icons.Default.Warning
        isTrigger -> Icons.Default.Bolt
        isAction -> Icons.Default.CheckCircle
        else -> Icons.Default.Info
    }

    val explanation = when {
        entry.message.contains("System Watcher") -> "Android attempted to re-enable volume limits. Intercepted and blocked."
        entry.message.contains("Auto Volume Drop Guard") -> "Android forced a volume drop. Instantly restored to previous level."
        entry.message.contains("CSD Force-Flush") -> "Wiped Sound Dose memory to prevent background attenuation."
        entry.message.contains("Boot") -> "Re-applied all safety bypasses after device restart."
        entry.message.contains("Screen") -> "Refreshed volume bypasses upon device wake/unlock."
        entry.message.contains("Charger") -> "Car charger status changed. Refreshed volume bypasses."
        entry.message.contains("Battery Saver") -> "Battery Saver mode changed. Restored unrestricted volume."
        entry.message.contains("Connected to") || entry.message.contains("Car Mode") -> "Connected to car audio or Bluetooth device. Applied safety bypasses."
        entry.message.contains("Doze Mode") -> "Phone power state changed during sleep. Refreshed volume bypasses."
        entry.message.contains("Bluetooth") || entry.message.contains("Wired") || entry.message.contains("USB") || entry.message.contains("Ringer") -> "Re-applied bypasses for new audio state or hardware."
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp).padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = entry.timestampStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.message.removePrefix(">>> TRIGGER: ").removePrefix("ACTION: "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
                if (explanation != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } }) }) { padding ->
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
                title = { Text(stringResource(R.string.game_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (gameActive) {
                Column(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.game_keep_volume_up), fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { highVolumeLevel }, modifier = Modifier.fillMaxWidth().height(20.dp).padding(vertical = 8.dp), color = if (highVolumeLevel > 0.3f) Color.Green else Color.Red)
                    Text(stringResource(R.string.game_score, score), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                // Play field sized from the real available space instead of fixed dp values.
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val targetSize = 100.dp
                    Box(
                        modifier = Modifier
                            .size(targetSize)
                            .offset(x = (maxWidth - targetSize) * targetX, y = (maxHeight - targetSize) * targetY)
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
                    Text(stringResource(R.string.game_over), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                    Text(stringResource(R.string.game_over_subtitle), fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.game_final_score, score), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {
                        score = 0
                        highVolumeLevel = 0.5f
                        gameActive = true
                    }) {
                        Text(stringResource(R.string.game_try_again))
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
