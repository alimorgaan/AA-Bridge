package dev.caraalink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.settings.AppSettings

@Composable
fun HelpScreen(onBack: () -> Unit, onRunSetup: () -> Unit) {
    val context = LocalContext.current
    SubScreen(stringResource(R.string.help), onBack) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            HelpCard(R.string.help_blank_q, R.string.help_blank_a, showMenu = true)
            HelpCard(R.string.help_tap_q, R.string.help_tap_a, showMenu = true)
            HelpCard(R.string.help_ask_q, R.string.help_ask_a, showMenu = false)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    AppSettings.setSetupComplete(context, false)
                    AppSettings.setDevModeConfirmed(context, false)
                    onRunSetup()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.help_setup_again)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpCard(question: Int, answer: Int, showMenu: Boolean) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(question), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(answer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showMenu) {
                Spacer(Modifier.height(12.dp))
                MenuHint(stringResource(R.string.menu_start_server))
            }
        }
    }
}
