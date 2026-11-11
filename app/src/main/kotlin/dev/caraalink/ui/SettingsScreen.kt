package dev.caraalink.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.theme.Dash
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.settings.SettingsActivity
import dev.caraalink.update.UpdateChecker

/** Page with a back arrow and a title; Settings, Help and About share it. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().dashGlow(Dash.GlowIdle), color = Color.Transparent, contentColor = Dash.Text) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Dash.Teal) }
                Spacer(Modifier.width(2.dp))
                Text(title.uppercase(), color = Dash.Teal, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onHelp: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    SubScreen(stringResource(R.string.settings), onBack) {
        WirelessCarRow()
        DashGroup {
            SettingsRow(rememberVectorPainter(Icons.Rounded.Refresh), stringResource(R.string.settings_updates)) {
                UpdateChecker.checkNow(context, manual = true)
            }
            SettingsRow(painterResource(R.drawable.ic_help), stringResource(R.string.help), onHelp)
            SettingsRow(rememberVectorPainter(Icons.Rounded.Info), stringResource(R.string.settings_about), onAbout)
            SettingsRow(painterResource(R.drawable.ic_tune), stringResource(R.string.settings_advanced)) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }
    }
}

/** The remembered car, with Forget; hidden when there is none. */
@Composable
private fun WirelessCarRow() {
    val context = LocalContext.current
    var car by remember { mutableStateOf(AppSettings.wirelessCar(context)) }
    val name = car?.name ?: return
    DashGroup {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.settings_wireless_car), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(name) },
            leadingContent = { Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                TextButton(onClick = {
                    AppSettings.setWirelessCar(context, null)
                    car = null
                }) { Text(stringResource(R.string.settings_forget), fontWeight = FontWeight.Bold) }
            },
        )
    }
}

/** A dashboard tile holding a few rows. */
@Composable
private fun DashGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(shape).background(Dash.Tile).border(1.dp, Dash.TileBorder, shape),
        content = content,
    )
}

@Composable
private fun SettingsRow(icon: Painter, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
