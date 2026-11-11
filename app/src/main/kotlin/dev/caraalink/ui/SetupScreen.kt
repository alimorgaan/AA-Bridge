package dev.caraalink.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.caraalink.R
import dev.caraalink.aa.AndroidAutoServer
import dev.caraalink.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val SETUP_PERMISSIONS = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
}.toTypedArray()

private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

fun permissionsGranted(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (context.granted(Manifest.permission.ACCESS_COARSE_LOCATION) || context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) &&
        context.granted(Manifest.permission.RECORD_AUDIO)

private fun progress(context: Context, serverRunning: Boolean) = SetupProgress(
    permissionsGranted = permissionsGranted(context),
    devModeConfirmed = AppSettings.devModeConfirmed(context),
    serverRunning = serverRunning,
    carConnectedOnce = AppSettings.carConnectedOnce(context),
)

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(progress(context, serverRunning = false).currentStep()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Re-check everything while visible: permissions change in system settings, the server in Android Auto.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val running = withContext(Dispatchers.IO) { AndroidAutoServer.isRunning() }
                step = progress(context, running).currentStep()
                if (step == 5) {
                    AppSettings.setSetupComplete(context, true)
                    onDone()
                    return@repeatOnLifecycle
                }
                delay(2_000)
            }
        }
    }
    // The resume after the dialog re-runs the check above; this only marks that we asked.
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requested = true
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
            BrandBar()
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))

                StepCard(1, step, R.string.setup_perm_title, R.string.setup_perm_body) {
                    Button(onClick = {
                        if (requested) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            askPermissions.launch(SETUP_PERMISSIONS)
                        }
                    }) { Text(stringResource(R.string.setup_perm_button)) }
                }
                StepCard(2, step, R.string.setup_dev_title, R.string.setup_dev_body) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { AndroidAutoServer.openSettings(context) }) { Text(stringResource(R.string.open_aa)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            AppSettings.setDevModeConfirmed(context, true)
                            step = 3
                        }) { Text(stringResource(R.string.setup_dev_done)) }
                    }
                }
                StepCard(3, step, R.string.setup_server_title, R.string.setup_server_body) {
                    Column {
                        MenuHint(stringResource(R.string.menu_start_server))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { AndroidAutoServer.openSettings(context) }) { Text(stringResource(R.string.open_aa)) }
                    }
                }
                StepCard(4, step, R.string.setup_car_title, R.string.setup_car_body, action = null)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One checklist row: ticked when [number] < [current], highlighted with its action when it is [current]. */
@Composable
private fun StepCard(number: Int, current: Int, title: Int, body: Int, action: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val done = number < current
    val active = number == current
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) colors.surfaceContainerHigh else colors.surfaceContainerLow),
        border = if (active) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(32.dp).background(
                    when {
                        done -> colors.primary
                        active -> colors.primaryContainer
                        else -> colors.surfaceVariant
                    },
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done || active) colors.onSurface else colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (active && action != null) {
                    Spacer(Modifier.height(12.dp))
                    action()
                }
            }
        }
    }
}
