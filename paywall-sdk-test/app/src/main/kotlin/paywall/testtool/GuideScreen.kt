package paywall.testtool

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val DOCS_URL = "https://developer.paywall.one/client-side-servisler/2.-yetkilendirme-sdk"

/** "Rehber" tab: SDK flow, response handling, error codes and what to do for each. */
@Composable
fun GuideScreen() {
    FlowSection()
    HandlingSection()
    ActionGuideSection()
    ErrorCodesSection()
}

@Composable
private fun FlowSection() {
    val context = LocalContext.current
    Section("SDK akışı") {
        listOf(
            "Merchant backend: session + token oluşturur, session bilgilerini token içine ekler.",
            "1. PaywallSdk.initPaywallSdk(environment, token, includeMasterpassSession = true) — SDK session bilgilerini " +
                "(SessionId, MasterpassToken, UserId, UserPhone) kendisi alır.",
            "2. PaywallSdk.providers.masterpass.init() — Masterpass hazırlanır (test aracında otomatik).",
            "3. İşlemler: getCardList (accountAccess), linkMerchant, addCard, deleteCard.",
            "4. Ödeme: payment.init (kayıtlı / manuel kart), payment.registerAndPurchase — sessionId SDK'dan otomatik.",
            "Cevap: 0000 → ✅ başarılı · 5001 / 5008 → verifyOtp · 5010 → 3D Secure sayfası · diğer → ❌ başarısız.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOCS_URL))) }) {
            Text("🔗 Detaylı dokümantasyon")
        }
    }
}

@Composable
private fun HandlingSection() {
    Section(
        "SDK cevabı nasıl işlenir",
        "Tüm fonksiyonlar aynı yapıyı döner (SdkResponse). Merchant akışı status'a göre dallanır; " +
            "ACTION_REQUIRED'da success da true'dur, bu yüzden success'e değil status'a bakılmalı.",
    ) {
        Mono(CodeSamples.HANDLING)
        listOf(
            "status: SUCCESS / ACTION_REQUIRED / FAILED",
            "source: SDK (SDK isteği göndermeden durdurdu) · PAYWALL · MASTERPASS",
            "actionType (yalnızca ACTION_REQUIRED): BANK_OTP, MASTERPASS_OTP, 3D, MERCHANT_LINK_REQUIRED",
            "errorCode + message (yalnızca FAILED): Masterpass kodları aşağıdaki listede",
            "data: işleme özel sonuç (kartlar, redirectUrl, paymentId, maskedCard...)",
            "providerMeta: httpStatus ve Masterpass responseCode (ör. 0000, 5010)",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ActionGuideSection() {
    Section("Hata → Ne yapmalı?", "Merchant'ın sık karşılaşacağı hatalar ve önerilen aksiyon.") {
        GuideData.errorActions.forEach { item ->
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${if (expanded) "▼" else "▶"} ${item.code}", fontWeight = FontWeight.Bold)
                if (expanded) {
                    Text(item.meaning, style = MaterialTheme.typography.bodyMedium)
                    item.actions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    item.relatedFlow?.let { Text("İlgili akış: $it", style = MaterialTheme.typography.bodySmall) }
                    item.note?.let { Text("Not: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ErrorCodesSection() {
    var query by rememberSaveable { mutableStateOf("") }
    Section("Masterpass hata kodları", "Masterpass'ın döndüğü hata kodları ve açıklamaları.") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Ara (kod veya açıklama)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GuideData.errorCodeGroups.forEach { (title, codes) ->
            val filtered = codes.filter {
                query.isBlank() || it.code.contains(query, true) || it.description.contains(query, true)
            }
            var expanded by remember { mutableStateOf(false) }
            val open = expanded || query.isNotBlank()
            Text(
                "${if (open) "▼" else "▶"} $title (${filtered.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            )
            if (open) {
                filtered.forEach { item ->
                    Column(modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                        Text(item.code, style = MaterialTheme.typography.labelLarge)
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
