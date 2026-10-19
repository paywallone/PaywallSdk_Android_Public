package paywall.testtool

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import paywall.sdk.PaywallSdk
import paywall.sdk.ui.compose.rememberPaywallCardNumberState
import paywall.sdk.ui.compose.rememberPaywallCvvState
import paywall.sdk.ui.compose.rememberPaywallOtpState
import paywall.sdk.ui.compose.rememberPaywallTermsState

class MainActivity : ComponentActivity() {

    private val network = NetworkCapture()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug-only SDK hook: every Paywall and Masterpass request appears in the "Ağ" tab.
        PaywallSdk.setNetworkListener(this, network)
        network.startLogcat()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                TestToolApp(network)
            }
        }
    }

    override fun onDestroy() {
        PaywallSdk.setNetworkListener(this, null)
        network.stopLogcat()
        super.onDestroy()
    }
}

private val TABS = listOf("Test", "Akış", "Ağ", "Rehber")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestToolApp(network: NetworkCapture) {
    // Configuration changes are handled in place (manifest), so remembered state and secure fields survive them.
    val inputs = SecureInputs(
        cardNumber = rememberPaywallCardNumberState(),
        cvv = rememberPaywallCvvState(),
        otp = rememberPaywallOtpState(),
        terms = rememberPaywallTermsState(),
    )
    val context = LocalContext.current
    val state = remember {
        val prefs = context.applicationContext.getSharedPreferences("test-tool-fields", Context.MODE_PRIVATE)
        HarnessState(inputs, PersistedFields(prefs))
    }
    var askRefresh by remember { mutableStateOf(false) }
    DisposableEffect(state) { onDispose(state::close) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Paywall Android SDK - Masterpass Test Harness", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "paywall-android-sdk ${PaywallSdk.VERSION} · ${state.environment.name}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { askRefresh = true }) { Text("↻ Yenile") }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { index, title ->
                        val count = when (index) {
                            1 -> " (${state.logs.size})"
                            2 -> " (${network.events.size})"
                            else -> ""
                        }
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title + count) })
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                0 -> HarnessScreen(state, inputs)
                1 -> FlowScreen(state, network)
                2 -> NetworkScreen(network)
                else -> GuideScreen()
            }
        }
    }

    if (askRefresh) {
        RefreshDialog(
            onRefresh = { clearSavedFields ->
                askRefresh = false
                state.refresh(clearSavedFields)
                network.clear()
                tab = 0
            },
            onDismiss = { askRefresh = false },
        )
    }
    state.otpRequest?.let { OtpDialog(state, it, inputs) }
    state.cardToDelete?.let { DeleteCardDialog(state, it) }
    if (state.threeDOpen) state.threeDUrl?.let { ThreeDDialog(state, it) }
}
