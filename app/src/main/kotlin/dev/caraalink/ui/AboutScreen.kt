package dev.caraalink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.caraalink.BuildConfig
import dev.caraalink.R

private const val CREDIT_URL = "https://github.com/JObersi10/CarAALink"
private const val LICENSE_URL = "https://github.com/alimorgaan/AA-Bridge/blob/main/LICENSE"
private const val SOURCE_URL = "https://github.com/alimorgaan/AA-Bridge"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SubScreen(stringResource(R.string.settings_about), onBack) {
        BrandBar()
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Link(stringResource(R.string.about_credit), CREDIT_URL)
            Link(stringResource(R.string.about_license), LICENSE_URL)
            Link(stringResource(R.string.about_source), SOURCE_URL)
        }
    }
}

@Composable
private fun Link(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } }.padding(vertical = 10.dp),
    )
}
