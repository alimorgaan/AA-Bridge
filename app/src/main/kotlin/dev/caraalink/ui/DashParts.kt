package dev.caraalink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caraalink.R
import dev.caraalink.ui.theme.Dash

enum class RingMode { IDLE, SPIN, FULL, ALERT }

/** The dashboard's soft light behind the ring; its colour follows the status. */
fun Modifier.dashGlow(glow: Color): Modifier = background(Dash.Background).drawBehind {
    drawRect(Brush.radialGradient(listOf(glow, Dash.Background), center = Offset(size.width / 2, size.height * 0.3f), radius = size.width * 1.1f))
}

/** The status ring: spins while searching, fills and breathes when connected, turns amber when it needs the driver. */
@Composable
fun StatusRing(mode: RingMode, icon: Painter, label: String, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "ring")
    val angle by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breath by motion.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breath")
    val colors = if (mode == RingMode.ALERT) listOf(Dash.Amber, Dash.Orange, Dash.Amber) else listOf(Dash.Teal, Dash.Cyan, Dash.Teal)
    val labelColor = when (mode) {
        RingMode.IDLE -> Dash.Muted
        RingMode.ALERT -> Dash.Amber
        else -> Dash.Teal
    }
    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 11.dp.toPx()
            val inset = 14.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Dash.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            if (mode == RingMode.IDLE) return@Canvas
            val brush = Brush.sweepGradient(colors)
            val sweep = if (mode == RingMode.SPIN) 95f else 360f
            val glowAlpha = if (mode == RingMode.SPIN) 0.35f else 0.3f * breath
            rotate(if (mode == RingMode.SPIN) angle else -90f) {
                // Wide faint strokes under the ring read as glow without a blur pass.
                drawArc(brush, 0f, sweep, false, topLeft, arcSize, alpha = glowAlpha * 0.5f, style = Stroke(width * 3.2f, cap = StrokeCap.Round))
                drawArc(brush, 0f, sweep, false, topLeft, arcSize, alpha = glowAlpha, style = Stroke(width * 1.9f, cap = StrokeCap.Round))
                drawArc(brush, 0f, sweep, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (mode == RingMode.IDLE) Dash.Muted else Dash.Text, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(6.dp))
            Text(label.uppercase(), color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
    }
}

/** A small dashboard gauge: a caps label over a value with a live dot. */
@Composable
fun DashTile(label: String, value: String, live: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Dash.Tile)
            .border(1.dp, Dash.TileBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label.uppercase(), color = Dash.Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (live) Color(0xFF34D399) else Color(0xFF56656B), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(value, color = Dash.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** The main action when things are fine: a teal outline that glows. */
@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Dash.Teal.copy(alpha = 0.07f))
            .border(BorderStroke(1.5.dp, Dash.Teal.copy(alpha = 0.85f)), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Dash.Teal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    }
}

/** The main action when something needs the driver. */
@Composable
fun AmberButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Dash.Amber, Dash.Orange)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF1A0F02), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    }
}

/** The quiet secondary action under the main one. */
@Composable
fun DashLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Dash.Teal,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** "AA BRIDGE" wordmark with the settings gear. */
@Composable
fun DashTopBar(onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.app_name).uppercase(), color = Dash.Teal, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = Dash.Teal)
        }
    }
}
