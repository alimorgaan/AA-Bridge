package dev.caraalink.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.caraalink.R
import dev.caraalink.aa.AndroidAutoServer
import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.connection.WirelessPhase
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SERVER_POLL_MS = 2_000L

/** True while the screen is resumed: polls the head unit server every 2 s. */
@Composable
fun rememberServerRunning(): Boolean {
    var running by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                running = withContext(Dispatchers.IO) { AndroidAutoServer.isRunning() }
                delay(SERVER_POLL_MS)
            }
        }
    }
    return running
}

@Composable
private fun rememberConnection(): ConnectionSnapshot {
    var snapshot by remember { mutableStateOf(ConnectionStateHolder.snapshot) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener: (ConnectionSnapshot) -> Unit = { s -> main.post { snapshot = s } }
        ConnectionStateHolder.addListener(listener)
        onDispose { ConnectionStateHolder.removeListener(listener) }
    }
    return snapshot
}

@Composable
fun BrandBar(modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_stat_bridge),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Starts the wireless search: the remembered car, or [address] when the driver picked one. */
fun startWireless(context: Context, address: String? = null, name: String? = null) {
    val intent = Intent(context, UsbLinkForegroundService::class.java).setAction(UsbLinkForegroundService.ACTION_START_WIRELESS)
    if (address != null) intent.putExtra(UsbLinkForegroundService.EXTRA_CAR_ADDRESS, address).putExtra(UsbLinkForegroundService.EXTRA_CAR_NAME, name)
    context.startForegroundService(intent)
}

private fun retryWireless(context: Context, connection: ConnectionSnapshot) {
    val car = carToRetry(connection, AppSettings.wirelessCar(context))
    startWireless(context, car?.address, car?.name)
}

private fun stopLink(context: Context) {
    context.startService(Intent(context, UsbLinkForegroundService::class.java).setAction(UsbLinkForegroundService.ACTION_STOP))
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenHelp: () -> Unit, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    val serverRunning = rememberServerRunning()
    val connection = rememberConnection()
    val carConnected = connection.phase.isCarConnected()
    val state = homeState(serverRunning, carConnected, connection.wireless)
    val retry = carToRetry(connection, AppSettings.wirelessCar(context))
    val rememberedName = retry?.name ?: ""
    val carName = connection.carName ?: rememberedName.ifEmpty { stringResource(R.string.state_connected) }
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val amber = if (dark) Color(0xFF451A03) to Color(0xFFFCD34D) else Color(0xFFFEF3C7) to Color(0xFFB45309)

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            BrandBar()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                val primary = colors.primaryContainer to colors.onPrimaryContainer
                if (state == HomeState.SEARCHING || state == HomeState.CONNECTING) {
                    BusyIcon(primary.first, primary.second)
                } else {
                    val (icon, tint) = when (state) {
                        HomeState.NEEDS_TAP -> painterResource(R.drawable.ic_touch_app) to amber
                        HomeState.CONNECTED -> painterResource(R.drawable.ic_car) to primary
                        HomeState.NOT_FOUND, HomeState.WIFI_OFF -> rememberVectorPainter(Icons.Rounded.Warning) to amber
                        else -> rememberVectorPainter(Icons.Rounded.Check) to primary
                    }
                    StatusIcon(icon, tint.first, tint.second)
                }
                Spacer(Modifier.height(24.dp))
                val title = when (state) {
                    HomeState.READY -> stringResource(R.string.home_ready_title)
                    HomeState.NEEDS_TAP -> stringResource(R.string.home_needs_tap_title)
                    HomeState.CONNECTED -> stringResource(R.string.home_connected_title)
                    HomeState.SEARCHING -> stringResource(R.string.home_searching_title, rememberedName)
                    HomeState.CONNECTING -> stringResource(R.string.home_connecting_title)
                    HomeState.NOT_FOUND -> if (connection.wireless == WirelessPhase.FAILED) {
                        stringResource(R.string.home_failed_title, rememberedName)
                    } else {
                        stringResource(R.string.home_not_found_title)
                    }
                    HomeState.WIFI_OFF -> stringResource(R.string.home_wifi_off_title)
                }
                val body = when (state) {
                    HomeState.READY -> R.string.home_ready_body
                    HomeState.NEEDS_TAP -> R.string.home_needs_tap_body
                    HomeState.CONNECTED -> R.string.home_connected_body
                    HomeState.SEARCHING -> R.string.home_searching_body
                    HomeState.CONNECTING -> R.string.home_connecting_body
                    HomeState.NOT_FOUND -> R.string.home_not_found_body
                    HomeState.WIFI_OFF -> R.string.home_wifi_off_body
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                HomeActions(state, connection, onOpenNearby)
                Spacer(Modifier.height(32.dp))
                Card(Modifier.fillMaxWidth()) {
                    StatusRow(
                        stringResource(R.string.row_server),
                        stringResource(if (serverRunning) R.string.state_running else R.string.state_off),
                        good = serverRunning,
                    )
                    HorizontalDivider(color = colors.surface)
                    StatusRow(
                        stringResource(R.string.row_car),
                        when {
                            !carConnected -> stringResource(R.string.state_not_connected)
                            connection.link == CarLink.WIRELESS -> stringResource(R.string.link_wireless, carName)
                            connection.link == CarLink.CABLE -> stringResource(R.string.link_cable, carName)
                            else -> carName
                        },
                        good = carConnected,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onOpenHelp) { Text(stringResource(R.string.help)) }
            }
        }
    }
}

/** The buttons under the status text; each state offers its own next step. */
@Composable
private fun HomeActions(state: HomeState, connection: ConnectionSnapshot, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    val wide = Modifier.fillMaxWidth().height(52.dp)
    when (state) {
        HomeState.NEEDS_TAP -> {
            Spacer(Modifier.height(24.dp))
            Button(onClick = { AndroidAutoServer.openSettings(context) }, modifier = wide) {
                Text(stringResource(R.string.open_aa), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(20.dp))
            MenuHint(stringResource(R.string.menu_start_server))
        }
        HomeState.READY -> {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onOpenNearby, modifier = wide) {
                Text(stringResource(R.string.home_connect_wireless), style = MaterialTheme.typography.titleSmall)
            }
        }
        HomeState.SEARCHING -> {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenNearby) { Text(stringResource(R.string.home_use_another_car)) }
        }
        HomeState.NOT_FOUND -> {
            connection.suggestion?.let { found ->
                Spacer(Modifier.height(16.dp))
                Card(onClick = { startWireless(context, found.address, found.name) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.home_found_nearby, found.name), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { retryWireless(context, connection) }, modifier = wide) {
                Text(stringResource(R.string.home_try_again), style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onOpenNearby) { Text(stringResource(R.string.home_choose_car)) }
        }
        HomeState.WIFI_OFF -> {
            Spacer(Modifier.height(24.dp))
            Button(onClick = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }, modifier = wide) {
                Text(stringResource(R.string.home_wifi_settings), style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = { retryWireless(context, connection) }) { Text(stringResource(R.string.home_try_again)) }
        }
        HomeState.CONNECTED -> if (connection.link == CarLink.WIRELESS) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { stopLink(context) }) { Text(stringResource(R.string.home_disconnect)) }
        }
        HomeState.CONNECTING -> {}
    }
}

@Composable
private fun BusyIcon(background: Color, tint: Color) {
    Box(Modifier.size(112.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = tint, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun StatusIcon(icon: Painter, background: Color, tint: Color) {
    Box(Modifier.size(112.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (good) FontWeight.SemiBold else FontWeight.Normal,
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
