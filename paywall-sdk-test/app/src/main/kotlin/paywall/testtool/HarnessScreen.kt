package paywall.testtool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import paywall.sdk.PaywallEnvironment
import paywall.sdk.ui.compose.PaywallCardNumberField
import paywall.sdk.ui.compose.PaywallCvvField
import paywall.sdk.ui.compose.PaywallTermsCheckBox

/** "Test" tab: configuration and sections A–D. */
@Composable
fun HarnessScreen(state: HarnessState, inputs: SecureInputs) {
    IntroSection()
    ConfigSection(state)
    SdkSessionSection(state)
    AccountCardSection(state)
    PaymentSection(state, inputs)
    OtpActionSection(state)
}

@Composable
private fun IntroSection() {
    Section("Nasıl kullanılır") {
        listOf(
            "1. Configuration: ortamı seç, merchant backend'inden alınan token'ı yapıştır.",
            "2. initPaywallSdk: SDK ve Masterpass hazırlanır.",
            "3. getCardList: kullanıcının kartları gelir (gerekirse linkMerchant + OTP).",
            "4. Ödeme: kayıtlı kartla, yeni kartla ya da kartı kaydederek öde. OTP / 3D istenirse ekran kendiliğinden açılır.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Hint(
            "Her butonun altında cevabın anlamı ve sıradaki adım (\"Ne yapılmalı?\"), merchant'ın yazacağı kod ve SDK'nın " +
                "döndüğü cevap görünür. Tüm çağrılar ve arka planda giden istekler Akış sekmesinde, ham HTTP trafiği " +
                "Ağ sekmesinde, hata kodları Rehber'de.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("✔ SUCCESS: tamam", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text("⚠ ACTION_REQUIRED: kullanıcıdan adım", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
        }
        Text("✖ FAILED: errorCode + message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ConfigSection(state: HarnessState) {
    val locked = state.sdkInit.success
    Section("Configuration") {
        Choice("Environment", PaywallEnvironment.entries, state.environment, { it.name }) {
            if (!locked) state.environment = it
        }
        OutlinedTextField(
            value = state.accessToken,
            onValueChange = { state.accessToken = it },
            label = { Text("Access Token") },
            enabled = !locked,
            supportingText = { Text("💡 Get from merchant backend (includes session info)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = state.includeMasterpassSession,
                onCheckedChange = { state.includeMasterpassSession = it },
                enabled = !locked,
            )
            Column {
                Text("Include Masterpass Session")
                Text(
                    "✅ When checked: Token contains session info (userId, userPhone auto-filled)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SdkSessionSection(state: HarnessState) {
    val context = LocalContext.current
    Section("A) SDK / Session Control Panel") {
        ActionRow(
            label = "PaywallSdk.initPaywallSdk",
            action = state.sdkInit,
            enabled = state.accessToken.isNotBlank() && !state.sdkInit.success,
            onClick = { state.initPaywallSdk(context) },
        )
        ActionRow(
            label = "PaywallSdk.providers.masterpass.init",
            action = state.providerInit,
            enabled = state.sessionId != null && !state.providerInit.success,
            onClick = state::initMasterpassProvider,
        )
        Text(
            "initPaywallSdk başarılı olunca providers.masterpass.init otomatik çağrılır.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AccountCardSection(state: HarnessState) {
    val ready = state.isProviderAndSessionReady
    Section("B) Account / Card Operations") {
        Field("User Phone (accountKey)", state.userPhone, { state.userPhone = it.trim() }, KeyboardType.Phone)
        Field("User ID", state.userId, { state.userId = it.trim() })
        ActionRow("PaywallSdk.providers.masterpass.getCardList", state.cardList, enabled = ready, onClick = state::accountAccess)

        if (state.cards.isNotEmpty()) {
            Text("Cards (${state.cards.size})", style = MaterialTheme.typography.titleSmall)
            state.cards.forEach { card ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.alias, fontWeight = FontWeight.Bold)
                        Text("${card.masked} · default: ${if (card.isDefault) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { state.confirmDeleteCard(card) }, enabled = !state.cardDelete.loading) {
                        Text("deleteCard", color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
        }

        ActionRow("PaywallSdk.providers.masterpass.linkMerchant", state.merchantLink, enabled = ready, onClick = state::merchantLink)
        if (state.cardDelete.response != null || state.cardDelete.error != null) {
            ResultView(state.cardDelete, title = if (state.cardDelete.success) "✔ Delete Success" else "✖ Delete Failed")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSection(state: HarnessState, inputs: SecureInputs) {
    val ready = state.isProviderAndSessionReady
    Section("C) Payment Test Area") {
        SubSection("Customer Information") {
            Field("Full Name *", state.customerFullName, { state.customerFullName = it })
            Field("Phone", state.customerPhone, { state.customerPhone = it }, KeyboardType.Phone)
            Field("Email *", state.customerEmail, { state.customerEmail = it }, KeyboardType.Email)
            Field("Identity Number *", state.customerIdentityNumber, { state.customerIdentityNumber = it }, KeyboardType.Number)
            Text(
                "* These fields are required and will be sent with every payment request. Phone is optional.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SubSection("Redirect URLs") {
            Field("Success URL", state.successUrl, { state.successUrl = it }, KeyboardType.Uri)
            Field("Fail URL", state.failUrl, { state.failUrl = it }, KeyboardType.Uri)
        }

        SubSection("Product List") {
            state.products.forEachIndexed { index, product ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallField("Code", product.code, Modifier.weight(1f)) { product.code = it }
                        SmallField("Name", product.name, Modifier.weight(1f)) { product.name = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallField("Qty", product.quantity, Modifier.weight(1f), KeyboardType.Number) { product.quantity = it }
                        SmallField("Price", product.price, Modifier.weight(1f), KeyboardType.Decimal) { product.price = it }
                        SmallField("VAT %", product.vatRate, Modifier.weight(1f), KeyboardType.Decimal) { product.vatRate = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total: ${product.total.toPlainString()}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.removeProduct(index) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = state::addProduct) { Text("+ Add Product") }
                Text("Total Amount: ${state.productTotal.toPlainString()}", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = state::updatePaymentAmountFromProducts) { Text("Update Payment Amount") }
            Field("Payment Amount", state.paymentAmount, { state.paymentAmount = it }, KeyboardType.Decimal)
        }

        SubSection("--- Registered Card Payment ---") {
            var expanded by remember { mutableStateOf(false) }
            val selected = state.selectedCard
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (state.cards.isNotEmpty()) expanded = it }) {
                OutlinedTextField(
                    value = selected?.let { "${it.masked} (${it.bin})" } ?: "Select a card",
                    onValueChange = {},
                    readOnly = true,
                    enabled = state.cards.isNotEmpty(),
                    label = { Text("Card") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.cards.forEachIndexed { index, card ->
                        DropdownMenuItem(
                            text = { Text("${card.masked} (${card.bin})") },
                            onClick = {
                                state.selectedCardIndex = index
                                expanded = false
                            },
                        )
                    }
                }
            }
            when {
                state.cards.isEmpty() -> Hint("No cards available. Get card list first.")
                selected == null -> Hint("Please select a card")
                else -> Hint("Selected: ${selected.alias} (${selected.bin})")
            }
            ActionRow(
                label = "PaywallSdk.payment.init (REGISTERED_CARD)",
                action = state.registeredPayment,
                enabled = ready && state.cards.isNotEmpty() && selected != null,
                onClick = state::payWithRegisteredCard,
                showActionRequired = true,
            )
        }

        SubSection("--- Manual Card Payment ---") {
            SecureFieldFrame(
                label = "Card Number",
                info = "BIN: ${inputs.cardNumber.bin ?: "-"} · ${inputs.cardNumber.digitCount} hane · geçerli: ${inputs.cardNumber.isValid}",
            ) { style, cursor, decoration ->
                PaywallCardNumberField(state = inputs.cardNumber, textStyle = style, cursorBrush = cursor, decorationBox = decoration)
            }
            Field("Card Holder Name", state.cardHolderName, { state.cardHolderName = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallField("Expiry Month (MM)", state.expiryMonth, Modifier.weight(1f), KeyboardType.Number) { state.expiryMonth = it.take(2) }
                SmallField("Expiry Year (YY)", state.expiryYear, Modifier.weight(1f), KeyboardType.Number) { state.expiryYear = it.take(2) }
            }
            SecureFieldFrame(label = "CVV", info = "${inputs.cvv.digitCount} hane") { style, cursor, decoration ->
                PaywallCvvField(state = inputs.cvv, textStyle = style, cursorBrush = cursor, decorationBox = decoration)
            }
            Field("Card Alias", state.cardAlias, { state.cardAlias = it })
            PaywallTermsCheckBox(state = inputs.terms) {
                Text("Masterpass kullanım koşullarını kabul ediyorum (kart kaydı için zorunlu)", style = MaterialTheme.typography.bodySmall)
            }
            ActionRow("PaywallSdk.providers.masterpass.addCard", state.addCard, enabled = ready, onClick = state::addCardToMasterpass)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.saveCardOnPayment, onCheckedChange = { state.saveCardOnPayment = it })
                Column {
                    Text("Kartımı kaydet ve öde", fontWeight = FontWeight.Medium)
                    Hint("İşaretliyse kart kaydı ve ödeme tek seferde yapılır: payment.registerAndPurchase")
                }
            }
            ActionRow(
                label = if (state.saveCardOnPayment) {
                    "PaywallSdk.payment.registerAndPurchase"
                } else {
                    "PaywallSdk.payment.init (MANUAL_CARD)"
                },
                action = state.manualPayment,
                enabled = ready,
                onClick = state::payWithManualCard,
                showActionRequired = true,
            )
        }

        state.threeDUrl?.let { url ->
            SubSection("3D Secure") {
                Mono(url)
                OutlinedButton(onClick = { state.threeDOpen = true }) { Text("3D sayfasını aç") }
            }
        }
    }
}

@Composable
private fun OtpActionSection(state: HarnessState) {
    Section(
        "D) OTP / Action Flow Handler",
        "ACTION_REQUIRED cevapları otomatik ele alınır: OTP penceresi açılır, 3D adresi C bölümünde gösterilir. " +
            "Tüm adımlar Akış sekmesinde.",
    ) {
        if (state.otpVerify.response != null || state.otpVerify.loading || state.otpVerify.error != null) {
            ResultView(state.otpVerify, title = "OTP Verify Response")
        } else {
            Hint("Henüz OTP doğrulaması yok.")
        }
    }
}

/** Button with status badge, error and collapsible "Response JSON". */
@Composable
private fun ActionRow(
    label: String,
    action: ActionState,
    enabled: Boolean,
    onClick: () -> Unit,
    showActionRequired: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(
                onClick = onClick,
                enabled = enabled && !action.loading,
                modifier = Modifier.weight(1f),
            ) { Text(if (action.loading) "⏳ Loading..." else label) }
            Badge(action, showActionRequired)
        }
        ResultView(action, title = null)
    }
}

@Composable
private fun Badge(action: ActionState, showActionRequired: Boolean) {
    val (text, color) = when {
        action.success -> "✔ Success" to MaterialTheme.colorScheme.primary
        showActionRequired && action.actionRequired -> "⚠ Action Required" to MaterialTheme.colorScheme.tertiary
        action.error != null -> "✖ Failed" to MaterialTheme.colorScheme.error
        else -> "○ Pending" to MaterialTheme.colorScheme.outline
    }
    Text(text, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(132.dp))
}

@Composable
private fun ResultView(action: ActionState, title: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let {
            Text(if (action.loading) "$it · ⏳ PENDING" else it, style = MaterialTheme.typography.titleSmall)
        }
        val guidance = action.guidance
        if (guidance != null) {
            GuidanceBox(guidance)
        } else {
            action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (action.response != null || action.code != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                action.code?.let {
                    TextButton(onClick = { action.showCode = !action.showCode }) {
                        Text("${if (action.showCode) "▼" else "▶"} Merchant kodu")
                    }
                }
                action.response?.let {
                    TextButton(onClick = { action.expanded = !action.expanded }) {
                        Text("${if (action.expanded) "▼" else "▶"} Response JSON")
                    }
                }
            }
            if (action.showCode) action.code?.let { Mono(it) }
            if (action.expanded) action.response?.let { Mono(SdkJson.pretty(SdkJson.response(it))) }
        }
    }
}

@Composable
private fun SubSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SmallField(
    label: String,
    value: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
