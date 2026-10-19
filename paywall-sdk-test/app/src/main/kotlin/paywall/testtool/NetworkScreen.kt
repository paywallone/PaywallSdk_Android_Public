package paywall.testtool

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import paywall.sdk.PaywallNetworkEvent
import paywall.sdk.PaywallNetworkSource

private enum class NetworkFilter(val title: String) { ALL("Tümü"), PAYWALL("Paywall"), MASTERPASS("Masterpass") }

/** "Ağ" tab: every HTTP call of the SDK, newest first. */
@Composable
fun NetworkScreen(network: NetworkCapture) {
    var filter by rememberSaveable { mutableStateOf(NetworkFilter.ALL) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val events = network.events.filter {
        when (filter) {
            NetworkFilter.ALL -> true
            NetworkFilter.PAYWALL -> it.source == PaywallNetworkSource.PAYWALL
            NetworkFilter.MASTERPASS -> it.source == PaywallNetworkSource.MASTERPASS
        }
    }

    Choice("Filtre", NetworkFilter.entries, filter, { it.title }) { filter = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val text = events.joinToString("\n\n" + "=".repeat(40) + "\n\n") { describe(it, network) }
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Paywall SDK network", text))) }
        }) { Text("Tümünü kopyala") }
        OutlinedButton(onClick = network::clear) { Text("Temizle") }
    }
    Text(
        "Bu sekme ham HTTP trafiğidir (tarayıcıdaki Network sekmesi gibi). SDK'nın merchant'a döndüğü cevap " +
            "(status, errorCode, data.redirectUrl...) Akış sekmesinde ve Test sekmesindeki \"Response JSON\" " +
            "alanlarındadır.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "Satıra dokununca istek ve cevap açılır. Masterpass istek gövdesi Masterpass'ın kendi logundan okunur " +
            "(kart numarası ve CVV şifreli görünür).",
        style = MaterialTheme.typography.bodySmall,
    )
    if (events.isEmpty()) Text("Henüz istek yok. Bir SDK fonksiyonu çağırın.", style = MaterialTheme.typography.bodySmall)
    events.forEach { event -> NetworkRow(event, network) }
}

@Composable
fun NetworkRow(event: PaywallNetworkEvent, network: NetworkCapture) {
    var expanded by remember(event) { mutableStateOf(false) }
    val failed = event.error != null || (event.statusCode ?: 0) >= 400
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(event.method, fontWeight = FontWeight.Bold)
                Text(event.statusCode?.toString() ?: "HATA", fontWeight = FontWeight.Bold)
                Text("${event.durationMs} ms", style = MaterialTheme.typography.bodySmall)
                Text(NetworkCapture.sourceName(event.source), style = MaterialTheme.typography.labelMedium)
                Text(time(event.startedAtMillis), style = MaterialTheme.typography.bodySmall)
            }
            Text(path(event.url), style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                HorizontalDivider()
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Part("URL", event.url)
                        Part("Request headers", headers(event, network).ifEmpty { listOf("(boş)") }.joinToString("\n"))
                        Part("Request", NetworkCapture.prettyJson(requestBody(event, network)))
                        Part("Response", event.error ?: NetworkCapture.prettyJson(event.responseBody))
                    }
                }
                TextButton(onClick = { expanded = false }) { Text("Kapat") }
            }
        }
    }
}

@Composable
private fun Part(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Mono(text)
    }
}

private fun headers(event: PaywallNetworkEvent, network: NetworkCapture): List<String> =
    if (event.source == PaywallNetworkSource.MASTERPASS) {
        network.masterpassRequestHeaders(event)
    } else {
        event.requestHeaders.map { (name, value) -> "$name: $value" }
    }

private fun requestBody(event: PaywallNetworkEvent, network: NetworkCapture): String? =
    event.requestBody ?: if (event.source == PaywallNetworkSource.MASTERPASS) network.masterpassRequestBody(event) else null

private fun describe(event: PaywallNetworkEvent, network: NetworkCapture): String = buildString {
    append("${event.method} ${event.url}\n")
    append("${NetworkCapture.sourceName(event.source)} · ${event.statusCode ?: "HATA"} · ${event.durationMs} ms · ")
    append(time(event.startedAtMillis)).append("\n\n")
    append("Request headers:\n").append(headers(event, network).joinToString("\n")).append("\n\n")
    append("Request:\n").append(NetworkCapture.prettyJson(requestBody(event, network))).append("\n\n")
    append("Response:\n").append(event.error ?: NetworkCapture.prettyJson(event.responseBody))
}

private fun path(url: String): String = url.substringAfter("://").substringAfter('/', "").let { "/$it" }

private fun time(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(millis))
