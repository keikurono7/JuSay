package com.example.jusay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.jusay.ui.theme.JuSayTheme

class MainActivity : ComponentActivity() {
    private lateinit var overlayManager: OverlayManager

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayManager = OverlayManager(this)
        enableEdgeToEdge()
        setContent {
            JuSayTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Voice OS Controller") },
                        )
                    },
                ) { innerPadding ->
                    ControlPanelScreen(
                        overlayManager = overlayManager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        overlayManager.hideMicOverlay()
        super.onDestroy()
    }
}

@Composable
fun ControlPanelScreen(overlayManager: OverlayManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(PREF_GROQ_API_KEY, "").orEmpty())
    }
    var agentEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_AGENT_ENABLED, true))
    }
    var overlaySwitchOn by remember {
        mutableStateOf(prefs.getBoolean(PREF_OVERLAY_ENABLED, false))
    }
    var status by remember { mutableStateOf("") }

    val overlayEnabled = Settings.canDrawOverlays(context)
    val micPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val serviceActive = VoiceAgentService.isServiceActive()
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var accessibilityPromptDismissed by remember { mutableStateOf(false) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) {
            "Microphone permission granted"
        } else {
            "Microphone permission denied"
        }
    }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) {
            "Notification permission granted"
        } else {
            "Notification permission denied"
        }
    }

    var micPermissionRequested by remember { mutableStateOf(false) }
    var notificationPermissionRequested by remember { mutableStateOf(false) }

    LaunchedEffect(micPermissionGranted, micPermissionRequested) {
        if (!micPermissionGranted && !micPermissionRequested) {
            micPermissionRequested = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(notificationPermissionRequested) {
        val needsNotificationRuntimePermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationRuntimePermission && !notificationPermissionRequested) {
            notificationPermissionRequested = true
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(overlaySwitchOn, overlayEnabled) {
        if (overlaySwitchOn && overlayEnabled) {
            overlayManager.showMicOverlay()
        } else {
            overlayManager.hideMicOverlay()
        }
    }

    LaunchedEffect(serviceActive) {
        if (serviceActive) {
            showAccessibilityDialog = false
            accessibilityPromptDismissed = false
        } else if (!accessibilityPromptDismissed) {
            showAccessibilityDialog = true
        }
    }

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = {
                showAccessibilityDialog = false
                accessibilityPromptDismissed = true
            },
            title = { Text("Enable Accessibility") },
            text = { Text("JuSay needs Accessibility access to control apps by voice.") },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDialog = false
                        accessibilityPromptDismissed = true
                    },
                ) {
                    Text("Later")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow(title = "Accessibility", isGood = serviceActive)
                StatusRow(title = "Microphone Permission", isGood = micPermissionGranted)
                StatusRow(title = "Overlay Permission", isGood = overlayEnabled)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Controls", style = MaterialTheme.typography.titleMedium)
                ToggleRow(
                    title = "Voice Agent",
                    subtitle = "Enable or disable listening and command execution",
                    checked = agentEnabled,
                    onCheckedChange = { checked ->
                        agentEnabled = checked
                        prefs.edit().putBoolean(PREF_AGENT_ENABLED, checked).apply()
                        status = if (checked) "Voice agent enabled" else "Voice agent disabled"
                    },
                )

                Divider()

                ToggleRow(
                    title = "Floating Mic Overlay",
                    subtitle = "Show small always-on mic button",
                    checked = overlaySwitchOn,
                    onCheckedChange = { checked ->
                        if (checked && !Settings.canDrawOverlays(context)) {
                            status = "Grant overlay permission first"
                            overlaySwitchOn = false
                        } else {
                            overlaySwitchOn = checked
                            prefs.edit().putBoolean(PREF_OVERLAY_ENABLED, checked).apply()
                            if (checked) {
                                overlayManager.showMicOverlay()
                                status = "Mic overlay enabled"
                            } else {
                                overlayManager.hideMicOverlay()
                                status = "Mic overlay disabled"
                            }
                        }
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Groq API Key", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            prefs.edit().putString(PREF_GROQ_API_KEY, apiKey.trim()).apply()
                            status = "API key saved"
                        },
                    ) {
                        Text("Save")
                    }
                    TextButton(
                        onClick = {
                            apiKey = ""
                            prefs.edit().remove(PREF_GROQ_API_KEY).apply()
                            status = "API key cleared"
                        },
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        Button(
            onClick = {
                status = VoiceAgentService.requestManualListening()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test Voice Command")
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (status.isNotBlank()) {
            Text(
                text = status,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun StatusRow(title: String, isGood: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Text(if (isGood) "ON" else "OFF")
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
fun ControlPanelPreview() {
    JuSayTheme {
        ControlPanelScreen(overlayManager = OverlayManager(LocalContext.current))
    }
}

private const val PREFS_NAME = "voice_os_prefs"
private const val PREF_GROQ_API_KEY = "groq_api_key"
private const val PREF_AGENT_ENABLED = "agent_enabled"
private const val PREF_OVERLAY_ENABLED = "overlay_enabled"