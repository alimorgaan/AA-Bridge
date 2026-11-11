package dev.caraalink.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.caraalink.ui.theme.Dash
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

/** The wordmark on screens without the settings gear. */
@Composable
fun BrandBar(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.app_name).uppercase(),
        modifier = modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        color = Dash.Teal,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.5.sp,
    )
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
fun HomeScreen(onOpenSettings: () -> Unit, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    val serverRunning = rememberServerRunning()
    val connection = rememberConnection()
    val carConnected = connection.phase.isCarConnected()
    val state = homeState(serverRunning, carConnected, connection.wireless)
    val searchName = carToRetry(connection, AppSettings.wirelessCar(context))?.name
    val carName = connection.carName ?: searchName ?: stringResource(R.string.state_connected)
    val alert = state == HomeState.NEEDS_TAP || state == HomeState.NOT_FOUND || state == HomeState.WIFI_OFF

    Column(
        Modifier
            .fillMaxSize()
            .dashGlow(
                when {
                    alert -> Dash.GlowAmber
                    state == HomeState.READY -> Dash.GlowIdle
                    else -> Dash.GlowTeal
                },
            )
            .systemBarsPadding(),
    ) {
        DashTopBar(onOpenSettings)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            val car = painterResource(R.drawable.ic_car)
            val warning = rememberVectorPainter(Icons.Rounded.Warning)
            when (state) {
                HomeState.READY -> StatusRing(RingMode.IDLE, car, stringResource(R.string.ring_ready))
                HomeState.SEARCHING -> StatusRing(RingMode.SPIN, car, stringResource(R.string.ring_searching))
                HomeState.CONNECTING -> StatusRing(RingMode.SPIN, car, stringResource(R.string.ring_connecting))
                HomeState.CONNECTED -> StatusRing(RingMode.FULL, car, stringResource(R.string.ring_connected))
                HomeState.NEEDS_TAP -> StatusRing(RingMode.ALERT, painterResource(R.drawable.ic_touch_app), stringResource(R.string.ring_needs_tap))
                HomeState.NOT_FOUND -> StatusRing(RingMode.ALERT, warning, stringResource(R.string.ring_not_found))
                HomeState.WIFI_OFF -> StatusRing(RingMode.ALERT, warning, stringResource(R.string.ring_wifi_off))
            }
            Spacer(Modifier.height(22.dp))
            val title = when (state) {
                HomeState.READY -> stringResource(R.string.home_ready_title)
                HomeState.NEEDS_TAP -> stringResource(R.string.home_needs_tap_title)
                HomeState.CONNECTED -> carName
                HomeState.SEARCHING, HomeState.CONNECTING -> searchName ?: stringResource(R.string.nearby_your_car)
                HomeState.NOT_FOUND -> if (connection.wireless == WirelessPhase.FAILED) {
                    stringResource(R.string.home_failed_title, searchName ?: "")
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
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Dash.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = Dash.Muted, textAlign = TextAlign.Center)
            if (state == HomeState.NEEDS_TAP) {
                Spacer(Modifier.height(20.dp))
                MenuHint(stringResource(R.string.menu_start_server))
            }
            if (state == HomeState.NOT_FOUND) {
                connection.suggestion?.let { found -> Spacer(Modifier.height(18.dp)); SuggestionTile(found.name) { startWireless(context, found.address, found.name) } }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state != HomeState.NEEDS_TAP && state != HomeState.WIFI_OFF) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashTile(
                        stringResource(R.string.tile_server),
                        stringResource(if (serverRunning) R.string.state_running else R.string.state_off),
                        live = serverRunning,
                        modifier = Modifier.weight(1f),
                    )
                    val (label, value) = when {
                        carConnected && connection.link == CarLink.WIRELESS -> R.string.tile_link to R.string.link_wireless_short
                        carConnected && connection.link == CarLink.CABLE -> R.string.tile_link to R.string.link_cable_short
                        carConnected -> R.string.tile_car to R.string.state_connected
                        state == HomeState.SEARCHING -> R.string.tile_car to R.string.state_searching
                        state == HomeState.CONNECTING -> R.string.tile_car to R.string.state_connecting
                        else -> R.string.tile_car to R.string.state_not_connected
                    }
                    DashTile(stringResource(label), stringResource(value), live = carConnected, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            HomeActions(state, connection, onOpenNearby)
        }
    }
}

/** The main and secondary actions; each state offers its own next step. */
@Composable
private fun HomeActions(state: HomeState, connection: ConnectionSnapshot, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        HomeState.READY -> GlowButton(stringResource(R.string.home_connect_wireless), onOpenNearby)
        HomeState.SEARCHING, HomeState.CONNECTING -> DashLink(stringResource(R.string.home_use_another_car), onOpenNearby)
        HomeState.CONNECTED -> if (connection.link == CarLink.WIRELESS) DashLink(stringResource(R.string.home_disconnect)) { stopLink(context) }
        HomeState.NEEDS_TAP -> AmberButton(stringResource(R.string.open_aa), { AndroidAutoServer.openSettings(context) })
        HomeState.NOT_FOUND -> {
            GlowButton(stringResource(R.string.home_try_again), { retryWireless(context, connection) })
            Spacer(Modifier.height(4.dp))
            DashLink(stringResource(R.string.home_choose_car), onOpenNearby)
        }
        HomeState.WIFI_OFF -> {
            AmberButton(stringResource(R.string.home_wifi_settings), { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) })
            Spacer(Modifier.height(4.dp))
            DashLink(stringResource(R.string.home_try_again)) { retryWireless(context, connection) }
        }
    }
}

/** A device with the remembered car's name, one tap away. */
@Composable
private fun SuggestionTile(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Dash.Tile)
            .border(1.dp, Dash.Teal.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = Dash.Teal)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.home_found_nearby, name), color = Dash.Text, style = MaterialTheme.typography.bodyLarge)
    }
}
