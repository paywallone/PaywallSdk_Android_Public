package paywall.testtool

import java.math.BigDecimal
import paywall.sdk.PaywallEnvironment

/**
 * The Kotlin a merchant app writes for each call, with the values used in this run. Card number, CVV and OTP come
 * from the Paywall secure fields (`cardNumberState.input` ...), so they appear as field references, never as values.
 */
object CodeSamples {

    private fun q(value: String?): String = if (value == null) "null" else "\"" + value.replace("\"", "\\\"") + "\""

    fun initPaywallSdk(environment: PaywallEnvironment, includeMasterpassSession: Boolean): String = """
        // Token: merchant backend'i Paywall'dan alır (SDK token üretmez).
        val response = PaywallSdk.initPaywallSdk(
            context = context,
            environment = PaywallEnvironment.${environment.name},
            token = tokenFromMerchantBackend,
            includeMasterpassSession = $includeMasterpassSession,
        )
    """.trimIndent()

    fun providerInit(): String = """
        // initPaywallSdk başarılı olduktan sonra, parametresiz.
        val response = PaywallSdk.providers.masterpass.init()
    """.trimIndent()

    fun getCardList(accountKey: String, userId: String): String = """
        val response = PaywallSdk.providers.masterpass.getCardList(
            GetCardListParams(
                accountKey = ${q(accountKey)},
                userId = ${q(userId)},
                accountKeyType = AccountKeyType.MSISDN,
            ),
        )
    """.trimIndent()

    fun linkMerchant(accountKey: String): String = """
        val response = PaywallSdk.providers.masterpass.linkMerchant(
            LinkMerchantParams(accountKey = ${q(accountKey)}),
        )
    """.trimIndent()

    fun deleteCard(accountKey: String, cardAlias: String): String = """
        val response = PaywallSdk.providers.masterpass.deleteCard(
            DeleteCardParams(accountKey = ${q(accountKey)}, cardAlias = ${q(cardAlias)}),
        )
    """.trimIndent()

    fun addCard(userId: String, accountKey: String, alias: String, holder: String, expiry: String, reference: String): String = """
        val response = PaywallSdk.providers.masterpass.addCard(
            AddCardParams(
                userId = ${q(userId)},
                accountKey = ${q(accountKey)},
                accountAliasName = ${q(alias)},
                cardHolderName = ${q(holder)},
                cardNumber = cardNumberState.input,   // PaywallCardNumberField
                expiryDate = ${q(expiry)},            // MMYY
                cvv = cvvState.cvvInput,              // PaywallCvvField
                termsConsent = termsState.input,      // PaywallTermsCheckBox
                requestReferenceNumber = ${q(reference)},
            ),
        )
    """.trimIndent()

    fun verifyOtp(): String = """
        val response = PaywallSdk.providers.masterpass.verifyOtp(
            VerifyOtpParams(otp = otpState.otpInput),   // PaywallOtpField
        )
    """.trimIndent()

    fun resendOtp(): String = "val response = PaywallSdk.providers.masterpass.resendOtp()"

    private fun detail(amount: BigDecimal, merchantCode: String, tracking: String, success: String, fail: String) = """
            paymentDetail = PaymentDetail(
                amount = BigDecimal("${amount.toPlainString()}"),
                currencyId = 1,
                merchantUniqueCode = ${q(merchantCode)},
                trackingCode = ${q(tracking)},
                successUrl = ${q(success)},
                failUrl = ${q(fail)},
                clientIp = "192.168.1.1",
                installment = 1,
            ),
            products = products,
            customer = customer,"""

    fun paymentRegistered(
        alias: String,
        bin: String,
        masked: String,
        amount: BigDecimal,
        merchantCode: String,
        tracking: String,
        success: String,
        fail: String,
    ): String = """
        val response = PaywallSdk.payment.init(
          PaymentInitParams(
            paymentSource = PaymentSource.REGISTERED_CARD,
            card = PaymentInitCard(ownerName = ${q(alias)}, cardBin = ${q(bin)}, cardMasked = ${q(masked)}),
            cardData = PaymentCardData(cardAlias = ${q(alias)}),${detail(amount, merchantCode, tracking, success, fail)}
          ),
        )
    """.trimIndent()

    fun paymentManual(
        holder: String,
        expiry: String,
        amount: BigDecimal,
        merchantCode: String,
        tracking: String,
        success: String,
        fail: String,
    ): String = """
        val response = PaywallSdk.payment.init(
          PaymentInitParams(
            paymentSource = PaymentSource.MANUAL_CARD,
            cardData = PaymentCardData(
                cardNumber = cardNumberState.input,   // PaywallCardNumberField
                ownerName = ${q(holder)},
                expiryDate = ${q(expiry)},            // MMYY
                cvv = cvvState.cvvInput,              // PaywallCvvField
            ),${detail(amount, merchantCode, tracking, success, fail)}
          ),
        )
    """.trimIndent()

    fun registerAndPurchase(
        accountKey: String,
        userId: String,
        holder: String,
        expiry: String,
        alias: String,
        amount: BigDecimal,
        merchantCode: String,
        tracking: String,
        success: String,
        fail: String,
    ): String = """
        val response = PaywallSdk.payment.registerAndPurchase(
          RegisterAndPurchaseParams(
            accountKey = ${q(accountKey)},
            merchantUserId = ${q(userId)},
            cardData = RegisterCardData(
                cardNumber = cardNumberState.input,   // PaywallCardNumberField
                cardHolderName = ${q(holder)},
                expiryDate = ${q(expiry)},            // MMYY
                cvv = cvvState.cvvInput,              // PaywallCvvField
                cardAlias = ${q(alias)},
            ),
            termsConsent = termsState.input,          // PaywallTermsCheckBox
            force3D = true,${detail(amount, merchantCode, tracking, success, fail)}
          ),
        )
    """.trimIndent()

    /** How every response is handled (shown once in the guide). */
    const val HANDLING: String = """when (response.status) {
    SdkStatus.SUCCESS -> {
        // İşlem tamam: response.data
    }
    SdkStatus.ACTION_REQUIRED -> when (response.actionType) {
        SdkActionType.BANK_OTP,
        SdkActionType.MASTERPASS_OTP -> showOtp()          // sonra verifyOtp(...)
        SdkActionType.THREE_D -> openWebView(response.data?.redirectUrl)
        SdkActionType.MERCHANT_LINK_REQUIRED -> linkMerchant()  // sonra getCardList(...)
        else -> Unit
    }
    SdkStatus.FAILED -> showError(response.errorCode, response.message)
}"""
}
