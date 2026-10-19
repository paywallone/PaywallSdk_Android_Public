package paywall.testtool

import paywall.sdk.ui.compose.PaywallCardNumberState
import paywall.sdk.ui.compose.PaywallSecureTextState
import paywall.sdk.ui.compose.PaywallTermsState

/** Paywall secure field states; created once at the top so the values survive tab switches. */
class SecureInputs(
    val cardNumber: PaywallCardNumberState,
    val cvv: PaywallSecureTextState,
    val otp: PaywallSecureTextState,
    val terms: PaywallTermsState,
)
