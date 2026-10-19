package paywall.testtool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import paywall.sdk.ui.compose.PaywallOtpField

@Composable
fun OtpDialog(state: HarnessState, request: OtpRequest, inputs: SecureInputs) {
    AlertDialog(
        onDismissRequest = state::cancelOtp,
        title = { Text(request.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.message)
                state.otpResendMessage?.let {
                    val error = "gönderilemedi" in it || "hata" in it
                    Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                SecureFieldFrame("OTP Code", "${inputs.otp.digitCount} hane") { style, cursor, decoration ->
                    PaywallOtpField(state = inputs.otp, textStyle = style, cursorBrush = cursor, decorationBox = decoration)
                }
            }
        },
        confirmButton = {
            Button(onClick = state::submitOtp, enabled = inputs.otp.digitCount > 0) { Text("Confirm") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = state::resendOtp, enabled = !state.otpResendLoading) {
                    Text(if (state.otpResendLoading) "Gönderiliyor..." else "Resend OTP")
                }
                TextButton(onClick = state::cancelOtp) { Text("Cancel") }
            }
        },
    )
}

/** "↻ Yenile": start over, keeping or clearing the saved fields. */
@Composable
fun RefreshDialog(onRefresh: (clearSavedFields: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uygulamayı yenile") },
        text = {
            Text(
                "SDK akışı, durumlar, kart listesi, OTP / 3D adımları, Akış ve Ağ kayıtları sıfırlanır. " +
                    "Girdiğin alanlar (token, telefon, müşteri, kart sahibi...) kalır.\n\n" +
                    "Kart numarası ve CVV güvenlik gereği hiç kaydedilmez. Koşul onay kutusu Masterpass tarafından " +
                    "programla kaldırılamaz.",
            )
        },
        confirmButton = {
            Button(onClick = { onRefresh(false) }) { Text("Yenile") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onRefresh(true) }) { Text("Alanları da sil") }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        },
    )
}

@Composable
fun DeleteCardDialog(state: HarnessState, card: CardRow) {
    AlertDialog(
        onDismissRequest = { state.cardToDelete = null },
        title = { Text("Confirm Delete Card") },
        text = { Text("Are you sure you want to delete card: ${card.alias}?") },
        confirmButton = {
            Button(onClick = state::deleteCard) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { state.cardToDelete = null }) { Text("Cancel") }
        },
    )
}

/**
 * 3D Secure page in a plain WebView rather than Masterpass' MPWebView. Reaching the merchant's success / fail URL
 * closes it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ThreeDDialog(state: HarnessState, url: String) {
    var currentUrl by remember { mutableStateOf(url) }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(currentUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.onThreeDFinished("3D: kullanıcı kapattı", currentUrl) }) { Text("Kapat") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                                    currentUrl = pageUrl
                                    val success = state.threeDSuccessUrl
                                    val fail = state.threeDFailUrl
                                    when {
                                        success != null && pageUrl.startsWith(success) ->
                                            state.onThreeDFinished("3D: successUrl'e dönüldü", pageUrl)
                                        fail != null && pageUrl.startsWith(fail) ->
                                            state.onThreeDFinished("3D: failUrl'e dönüldü", pageUrl)
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}
