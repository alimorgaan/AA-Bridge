package dev.caraalink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.caraalink.R
import dev.caraalink.support.SupportAsk
import dev.caraalink.ui.theme.Dash

/** The ask is Arabic on every phone, so it lays out right to left even on an English phone. */
@Composable
private fun Rtl(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)

@Composable
fun SupportScreen(onBack: () -> Unit, onSupported: () -> Unit) = Rtl {
    val uri = LocalUriHandler.current
    SubScreen(stringResource(R.string.support_action), onBack) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.support_amount_hint), color = Dash.Text, fontSize = 17.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            GlowButton(stringResource(R.string.support_instapay), { runCatching { uri.openUri(SupportAsk.INSTAPAY_URL) } })
            Spacer(Modifier.height(14.dp))
            GlowButton(stringResource(R.string.support_paypal), { runCatching { uri.openUri(SupportAsk.PAYPAL_URL) } })
            Spacer(Modifier.height(20.dp))
            DashLink(stringResource(R.string.support_already), onSupported)
        }
    }
}

@Composable
fun SupportDialog(onSupport: () -> Unit, onDismiss: () -> Unit, onSupported: () -> Unit) = Dialog(onDismissRequest = onDismiss) {
    Rtl {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Dash.Tile).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.support_if_helped), color = Dash.Teal, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.support_popup_body), color = Dash.Text, fontSize = 16.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            GlowButton(stringResource(R.string.support_action), onSupport)
            Spacer(Modifier.height(6.dp))
            DashLink(stringResource(R.string.support_not_now), onDismiss)
            DashLink(stringResource(R.string.support_already), onSupported)
        }
    }
}

@Composable
fun SupportBanner(onClick: () -> Unit, modifier: Modifier = Modifier) = Rtl {
    val ink = Color(0xFF032724)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dash.Teal).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(painterResource(R.drawable.ic_coffee), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.support_banner), color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}
