package dev.caraalink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import dev.caraalink.ui.theme.Dash
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import dev.caraalink.wireless.NearbyDevice
import dev.caraalink.wireless.WirelessLink
import dev.caraalink.wireless.listOrder
import kotlinx.coroutines.delay
import java.net.InetAddress

internal val NEARBY_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

/** Pick the car once: a live Wi-Fi Direct scan, remembered car first. */
@Composable
fun NearbyCarsScreen(onBack: () -> Unit, onConnecting: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val remembered = remember { AppSettings.wirelessCar(context) }
    var granted by remember { mutableStateOf(context.checkSelfPermission(NEARBY_PERMISSION) == PackageManager.PERMISSION_GRANTED) }
    var asks by remember { mutableStateOf(0) }
    var wifiOn by remember { mutableStateOf(true) }
    var peers by remember { mutableStateOf(emptyList<NearbyDevice>()) }
    var connecting by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asks++
    }

    // Settings and the Wi-Fi panel can change both while this screen is away, so re-read them on resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                granted = context.checkSelfPermission(NEARBY_PERMISSION) == PackageManager.PERMISSION_GRANTED
                wifiOn = context.getSystemService(WifiManager::class.java)?.isWifiEnabled != false
                delay(2_000)
            }
        }
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(NEARBY_PERMISSION) }

    if (granted && wifiOn) {
        DisposableEffect(Unit) {
            val link = WirelessLink(context, DiagnosticLogger(context, "wireless"), object : WirelessLink.Listener {
                override fun onPeers(found: List<NearbyDevice>) {
                    if (connecting == null) peers = listOrder(found, remembered)
                }
                override fun onCarReachable(host: String, local: InetAddress?) {}
                override fun onGroupLost() {}
                override fun onWifiOff() { wifiOn = false }
            })
            link.scan()
            onDispose { link.close() }
        }
    }

    SubScreen(stringResource(R.string.nearby_title), onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            when {
                !granted -> NoticeCard(
                    title = stringResource(R.string.nearby_permission_title),
                    body = stringResource(R.string.nearby_permission_body),
                    button = stringResource(R.string.setup_perm_button),
                ) {
                    // After "Don't allow" with no rationale left, Android shows no dialog; only app settings can grant it.
                    if (asks > 0 && activity?.shouldShowRequestPermissionRationale(NEARBY_PERMISSION) == false) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        ask.launch(NEARBY_PERMISSION)
                    }
                }
                !wifiOn -> NoticeCard(
                    title = stringResource(R.string.home_wifi_off_title),
                    body = stringResource(R.string.home_wifi_off_body),
                    button = stringResource(R.string.home_wifi_settings),
                    amber = true,
                ) { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
                else -> {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.nearby_looking), style = MaterialTheme.typography.bodyLarge)
                    }
                    peers.forEach { device ->
                        val isRemembered = remembered?.address.equals(device.address, ignoreCase = true)
                        val note = when {
                            connecting == device.address -> R.string.home_connecting_title
                            isRemembered -> R.string.nearby_your_car
                            else -> R.string.nearby_tap
                        }
                        NearbyRow(device, stringResource(note), glow = isRemembered, enabled = connecting == null) {
                            connecting = device.address
                            startWireless(context, device.address, device.name)
                            onConnecting()
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.nearby_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One nearby device as a dashboard tile; the remembered car glows. */
@Composable
private fun NearbyRow(device: NearbyDevice, note: String, glow: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(Dash.Tile)
            .border(1.dp, if (glow) Dash.Teal.copy(alpha = 0.8f) else Dash.TileBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).background(if (device.isCar) Dash.GlowTeal else Dash.Track, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (device.isCar) R.drawable.ic_car else R.drawable.ic_devices),
                contentDescription = null,
                tint = if (device.isCar) Dash.Teal else Dash.Muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(device.name, color = Dash.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(note, color = Dash.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, button: String, amber: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(shape).background(Dash.Tile).border(1.dp, Dash.TileBorder, shape).padding(20.dp),
    ) {
        Text(title, color = Dash.Text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Dash.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        if (amber) AmberButton(button, onClick) else GlowButton(button, onClick)
    }
}
