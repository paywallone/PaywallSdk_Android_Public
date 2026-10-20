package paywall.testtool

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * "Akış": every SDK call as one card, newest first. Answers three questions for merchants and testers:
 * how the call is written, which HTTP requests it makes, what the SDK returns and what to do next.
 */
@Composable
fun FlowScreen(state: HarnessState, network: NetworkCapture) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Section(
        "Akış nasıl okunur",
        "Her kart bir SDK çağrısıdır. Üstte cevabın anlamı ve sıradaki adım; altta açılır bölümler: " +
            "① merchant'ın yazdığı kod · ② SDK'ya verilen parametreler · ③ SDK'nın arka planda attığı HTTP istekleri " +
            "(Paywall / Masterpass) · ④ SDK'nın döndüğü cevap.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val json = state.logsAsJson()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Paywall SDK akış", json))) }
            }) { Text("Akışı kopyala (JSON)") }
            OutlinedButton(onClick = state::clearLogs) { Text("Temizle") }
        }
        Expandable("Anlık durum (Current State)") { SelectionContainer { Mono(SdkJson.pretty(state.currentState)) } }
    }

    if (state.logs.isEmpty()) {
        Text("Henüz çağrı yok. Test sekmesinden \"PaywallSdk.initPaywallSdk\" ile başlayın.", style = MaterialTheme.typography.bodyMedium)
    }
    val format = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    state.logs.asReversed().forEachIndexed { reversedIndex, step ->
        val number = state.logs.size - reversedIndex
        FlowCard(number, step, format, network)
    }
}

@Composable
private fun FlowCard(number: Int, step: FlowStep, format: SimpleDateFormat, network: NetworkCapture) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                Text(step.actionName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusChip(step)
            }
            Text(
                "${format.format(step.time)} · ${step.endedAt - step.startedAt} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step.guidance?.let { GuidanceBox(it) }
            step.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            step.code?.let { Expandable("① Merchant kodu") { SelectionContainer { Mono(it) } } }
            step.request?.let { Expandable("② SDK'ya verilen parametreler") { SelectionContainer { Mono(SdkJson.pretty(it)) } } }
            if (step.response != null) {
                val events = network.events.filter { it.startedAtMillis in (step.startedAt - 50)..(step.endedAt + 50) }
                    .sortedBy { it.startedAtMillis }
                Expandable("③ Arka planda giden HTTP istekleri (${events.size})") {
                    if (events.isEmpty()) {
                        Text(
                            "İstek yok: SDK cevabı istek göndermeden üretti (ör. parametre kontrolü).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    events.forEach { NetworkRow(it, network) }
                }
                Expandable("④ SDK'nın döndüğü cevap (SdkResponse)") {
                    SelectionContainer { Mono(SdkJson.pretty(step.response)) }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(step: FlowStep) {
    val status = step.normalized?.get("status")?.asString
    val (label, color) = when (status) {
        "SUCCESS" -> "SUCCESS" to MaterialTheme.colorScheme.primary
        "ACTION_REQUIRED" -> {
            val action = step.response?.get("actionType")?.takeUnless { it.isJsonNull }?.asString
            "ACTION_REQUIRED${action?.let { " · $it" } ?: ""}" to MaterialTheme.colorScheme.tertiary
        }
        "FAILED" -> "FAILED" to MaterialTheme.colorScheme.error
        else -> "BİLGİ" to MaterialTheme.colorScheme.outline
    }
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** "Ne yapılmalı?" panel, colored by outcome. */
@Composable
fun GuidanceBox(guidance: Guidance) {
    val (background, accent) = when (guidance.tone) {
        Tone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.ACTION -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Tone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(guidance.title, color = accent, fontWeight = FontWeight.Bold)
        Text(guidance.meaning, color = accent, style = MaterialTheme.typography.bodySmall)
        if (guidance.nextSteps.isNotEmpty()) {
            Text("Ne yapılmalı?", color = accent, style = MaterialTheme.typography.labelLarge)
            guidance.nextSteps.forEach { Text("→ $it", color = accent, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            "${if (open) "▼" else "▶"} $title",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) content()
    }
}
