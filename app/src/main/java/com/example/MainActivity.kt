package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.DeepSpaceNavy
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.NovabornBotTheme
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.PulseViolet
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            NovabornBotTheme {
                NovabornControlDeck()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovabornControlDeck(
    viewModel: BotControlViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val botState by viewModel.botState.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val accessibilityConnected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()

    var showCalibration by remember { mutableStateOf(false) }

    // Refresh permission statuses whenever user returns from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { viewModel.refreshPermissions(context) },
    )

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { viewModel.refreshPermissions(context) },
    )

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(context, FloatingUIService::class.java).apply {
                    action = FloatingUIService.ACTION_START
                    putExtra(FloatingUIService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(FloatingUIService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                BotStateRepository.updateStatus(
                    message = "Screen capture cancelled",
                    error = "MediaProjection permission is required to scan the game screen locally.",
                )
            }
        },
    )

    val isOverlayGranted = permissions.overlayGranted
    val isAccessibilityReady = accessibilityConnected || permissions.accessibilityConnected
    val allCorePermissionsReady = isOverlayGranted && isAccessibilityReady

    Scaffold(
        containerColor = DeepSpaceNavy,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0B1224),
                            DeepSpaceNavy,
                            Color(0xFF070A14),
                        )
                    )
                )
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Hero Tactical Header
                HeroBannerCard(
                    isOverlayRunning = botState.isOverlayRunning,
                    isBotActive = botState.isBotActive,
                    framesAnalyzed = botState.framesAnalyzed,
                )

                // Primary Launch / Stop CTA
                PrimaryHudActionCard(
                    isOverlayRunning = botState.isOverlayRunning,
                    allCorePermissionsReady = allCorePermissionsReady,
                    onLaunchHud = {
                        val projectionManager = context.getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE
                        ) as MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onStopHud = {
                        viewModel.stopFloatingOverlay(context)
                    },
                )

                // Permissions Checklist
                SectionTitle(
                    icon = Icons.Default.Layers,
                    title = "System Permissions",
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionRowCard(
                        icon = Icons.Default.Layers,
                        title = "Display Over Other Apps",
                        subtitle = "Draws the floating control HUD over Pixel Universe: Novaborn.",
                        granted = isOverlayGranted,
                        actionLabel = if (isOverlayGranted) "Active" else "Grant",
                        testTag = "btn_permission_overlay",
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            settingsLauncher.launch(intent)
                        },
                    )

                    PermissionRowCard(
                        icon = Icons.Default.AccessibilityNew,
                        title = "Accessibility Service",
                        subtitle = "Dispatches taps and virtual joystick orbital swipes without root.",
                        granted = isAccessibilityReady,
                        actionLabel = if (isAccessibilityReady) "Connected" else "Enable",
                        testTag = "btn_permission_accessibility",
                        onClick = {
                            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionRowCard(
                            icon = Icons.Default.NotificationsActive,
                            title = "HUD Notifications",
                            subtitle = "Keeps the foreground screen capture service stable.",
                            granted = permissions.notificationGranted,
                            actionLabel = if (permissions.notificationGranted) "Allowed" else "Allow",
                            testTag = "btn_permission_notifications",
                            onClick = {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            },
                        )
                    }
                }

                // Target & Loot Configuration
                SectionTitle(
                    icon = Icons.Default.Radar,
                    title = "Combat & Loot Rules",
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                    border = BorderStroke(1.dp, OutlineSubtle),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Target Map",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MAP_TARGETS.forEach { map ->
                                val selected = botState.targetMap == map
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setTargetMap(map) },
                                    modifier = Modifier.testTag("chip_map_${map.lowercase().replace(" ", "_")}"),
                                    label = {
                                        Text(
                                            text = map,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PulseViolet.copy(alpha = 0.25f),
                                        selectedLabelColor = ElectricCyan,
                                        containerColor = TacticalSurfaceElevated,
                                        labelColor = TextSecondary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        selectedBorderColor = PulseViolet,
                                        borderColor = OutlineSubtle,
                                    ),
                                )
                            }
                        }

                        Text(
                            text = "Target Alien",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ALIEN_TARGETS.forEach { alien ->
                                val selected = botState.targetAlien == alien
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setTargetAlien(alien) },
                                    modifier = Modifier.testTag("chip_alien_${alien.lowercase()}"),
                                    label = {
                                        Text(
                                            text = alien,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = ElectricCyan,
                                        containerColor = TacticalSurfaceElevated,
                                        labelColor = TextSecondary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        selectedBorderColor = ElectricCyan,
                                        borderColor = OutlineSubtle,
                                    ),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(TacticalSurfaceElevated)
                                .clickable {
                                    viewModel.setCollectKeyBoxes(!botState.collectKeyBoxes)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Collect Key Boxes",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "When enabled, the bot waits 3.2s after tapping Collect on key boxes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = botState.collectKeyBoxes,
                                onCheckedChange = { viewModel.setCollectKeyBoxes(it) },
                                modifier = Modifier.testTag("switch_collect_key_boxes"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DeepSpaceNavy,
                                    checkedTrackColor = ElectricCyan,
                                ),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(TacticalSurfaceElevated)
                                .clickable {
                                    viewModel.setAttackAliens(!botState.attackAliens)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Attack Aliens",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Disable for Pacifist/Farming mode (ignores aliens, farms boxes only).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = botState.attackAliens,
                                onCheckedChange = { viewModel.setAttackAliens(it) },
                                modifier = Modifier.testTag("switch_attack_aliens"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DeepSpaceNavy,
                                    checkedTrackColor = ElectricCyan,
                                ),
                            )
                        }
                    }
                }

                // Calibration Card
                CalibrationSection(
                    expanded = showCalibration,
                    calibration = botState.calibration,
                    onToggleExpand = { showCalibration = !showCalibration },
                    onCalibrationChange = { viewModel.updateCalibration(it) },
                    onReset = { viewModel.resetCalibration() },
                )

                // Live Telemetry Card
                TelemetryStatusCard(botState = botState)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HeroBannerCard(
    isOverlayRunning: Boolean,
    isBotActive: Boolean,
    framesAnalyzed: Int,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF132242),
                            Color(0xFF1A143B),
                        )
                    )
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ElectricCyan.copy(alpha = 0.16f))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.RocketLaunch,
                                contentDescription = "Novaborn Bot",
                                tint = ElectricCyan,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "NOVABORN BOT",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Pixel Universe: Novaborn • Offline Pixel Engine",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan,
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusBadge(
                        label = if (isOverlayRunning) "HUD ACTIVE" else "HUD IDLE",
                        color = if (isOverlayRunning) EmeraldReady else TextSecondary,
                    )
                    StatusBadge(
                        label = if (isBotActive) "LOCAL BOT ON" else "STANDBY",
                        color = if (isBotActive) ElectricCyan else AmberAlert,
                    )
                    StatusBadge(
                        label = "FRAMES: $framesAnalyzed",
                        color = PulseViolet,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun PrimaryHudActionCard(
    isOverlayRunning: Boolean,
    allCorePermissionsReady: Boolean,
    onLaunchHud: () -> Unit,
    onStopHud: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, OutlineSubtle),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = null,
                    tint = ElectricCyan,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Autonomous Execution Control",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = if (allCorePermissionsReady) {
                    "Ready. Launching the HUD will prompt for MediaProjection screen capture so you can switch into the game."
                } else {
                    "Grant Display Over Other Apps and Accessibility Service above before launching the floating HUD."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            if (!isOverlayRunning) {
                Button(
                    onClick = onLaunchHud,
                    enabled = allCorePermissionsReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_launch_floating_hud"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = DeepSpaceNavy,
                        disabledContainerColor = TacticalSurfaceElevated,
                        disabledContentColor = TextSecondary,
                    ),
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Launch Floating HUD",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Button(
                    onClick = onStopHud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_stop_floating_hud"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonError,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Floating HUD",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun PermissionRowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(
            1.dp,
            if (granted) EmeraldReady.copy(alpha = 0.45f) else AmberAlert.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else icon,
                contentDescription = title,
                tint = if (granted) EmeraldReady else AmberAlert,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(testTag),
                border = BorderStroke(
                    1.dp,
                    if (granted) EmeraldReady.copy(alpha = 0.5f) else ElectricCyan,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (granted) EmeraldReady else ElectricCyan,
                ),
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    expanded: Boolean,
    calibration: HudCalibration,
    onToggleExpand: () -> Unit,
    onCalibrationChange: (HudCalibration) -> Unit,
    onReset: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, OutlineSubtle),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { onToggleExpand() }
                    .testTag("btn_toggle_calibration"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = PulseViolet,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Proportional HUD Calibration",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Tune screen coordinates (0%-100%) to match your device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand calibration",
                    tint = TextSecondary,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RatioSlider("Joystick X", calibration.joystickCenterX) {
                        onCalibrationChange(calibration.copy(joystickCenterX = it))
                    }
                    RatioSlider("Joystick Y", calibration.joystickCenterY) {
                        onCalibrationChange(calibration.copy(joystickCenterY = it))
                    }
                    RatioSlider("ATTACK Button X", calibration.attackButtonX) {
                        onCalibrationChange(calibration.copy(attackButtonX = it))
                    }
                    RatioSlider("ATTACK Button Y", calibration.attackButtonY) {
                        onCalibrationChange(calibration.copy(attackButtonY = it))
                    }
                    RatioSlider("COLLECT Button X", calibration.collectButtonX) {
                        onCalibrationChange(calibration.copy(collectButtonX = it))
                    }
                    RatioSlider("COLLECT Button Y", calibration.collectButtonY) {
                        onCalibrationChange(calibration.copy(collectButtonY = it))
                    }
                    RatioSlider("CONFIG Button X", calibration.configButtonX) {
                        onCalibrationChange(calibration.copy(configButtonX = it))
                    }
                    RatioSlider("CONFIG Button Y", calibration.configButtonY) {
                        onCalibrationChange(calibration.copy(configButtonY = it))
                    }

                    TextButton(
                        onClick = onReset,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("btn_reset_calibration"),
                    ) {
                        Text("Reset to defaults", color = ElectricCyan)
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.04f..0.96f,
            colors = SliderDefaults.colors(
                thumbColor = ElectricCyan,
                activeTrackColor = ElectricCyan,
            ),
        )
    }
}

@Composable
private fun TelemetryStatusCard(botState: BotRuntimeState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(
            1.dp,
            if (botState.lastError != null) CrimsonError.copy(alpha = 0.6f) else OutlineSubtle,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Local Pixel Vision Telemetry",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = botState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (botState.lastError != null) CrimsonError else ElectricCyan,
            )

            botState.lastDecision?.let { decision ->
                Surface(
                    color = TacticalSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "accion=${decision.accion} | config=${decision.config} | dx=${decision.dx}, dy=${decision.dy}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "tapAttackOnce=${decision.tapAttackOnce} | willContinue=${decision.willContinue} | waitKey=${decision.esperarCajaLlave}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                        Text(
                            text = "Reason: ${decision.razon}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            botState.lastError?.let { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = CrimsonError,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrimsonError,
                    )
                }
            }
        }
    }
}
