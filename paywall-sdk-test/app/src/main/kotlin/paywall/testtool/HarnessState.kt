package paywall.testtool

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.util.Date
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import paywall.sdk.PaywallEnvironment
import paywall.sdk.PaywallSdk
import paywall.sdk.SdkActionType
import paywall.sdk.SdkResponse
import paywall.sdk.SdkStatus
import paywall.sdk.masterpass.AccountKeyType
import paywall.sdk.masterpass.AddCardParams
import paywall.sdk.masterpass.DeleteCardParams
import paywall.sdk.masterpass.GetCardListParams
import paywall.sdk.masterpass.LinkMerchantParams
import paywall.sdk.masterpass.VerifyOtpParams
import paywall.sdk.payment.Customer
import paywall.sdk.payment.MasterpassPaymentInitResult
import paywall.sdk.payment.PaymentCardData
import paywall.sdk.payment.PaymentDetail
import paywall.sdk.payment.PaymentInitCard
import paywall.sdk.payment.PaymentInitParams
import paywall.sdk.payment.PaymentSource
import paywall.sdk.payment.Product
import paywall.sdk.payment.RegisterAndPurchaseParams
import paywall.sdk.payment.RegisterCardData
import paywall.sdk.payment.RegisteredCardPaymentParams

/** Loading, result and last response of one action button. */
class ActionState {
    var loading by mutableStateOf(false)
    var success by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var response by mutableStateOf<SdkResponse<*>?>(null)
    var expanded by mutableStateOf(false)

    /** "Ne yapılmalı?" for the last response, and the merchant code of the last call. */
    var guidance by mutableStateOf<Guidance?>(null)
    var code by mutableStateOf<String?>(null)
    var showCode by mutableStateOf(false)

    val actionRequired: Boolean get() = response?.status == SdkStatus.ACTION_REQUIRED

    fun start() {
        loading = true
        success = false
        error = null
        response = null
        guidance = null
    }

    fun reset() {
        start()
        loading = false
    }
}

/** Row of the saved cards table. */
class CardRow(val alias: String, val masked: String, val bin: String, val isDefault: Boolean, val uniqueCardNumber: String?)

/** Editable row of the product list. */
class ProductRow(code: String, name: String, quantity: String, price: String, vatRate: String) {
    var code by mutableStateOf(code)
    var name by mutableStateOf(name)
    var quantity by mutableStateOf(quantity)
    var price by mutableStateOf(price)
    var vatRate by mutableStateOf(vatRate)

    /** Price × quantity; VAT is shown but not added. */
    val total: BigDecimal
        get() {
            val p = price.replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO
            val q = quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            return p.multiply(q)
        }
}

/**
 * One SDK call in the "Akış" tab: merchant code, request, SDK response, guidance. The HTTP requests it caused are
 * the network events between [startedAt] and [endedAt].
 */
class FlowStep(
    val startedAt: Long,
    val endedAt: Long,
    val actionName: String,
    val code: String? = null,
    val request: JsonObject? = null,
    val response: JsonObject? = null,
    val normalized: JsonObject? = null,
    val guidance: Guidance? = null,
    val error: String? = null,
) {
    val time: Date get() = Date(startedAt)
}

/** OTP dialog waiting for input, and the action that asked for it. */
class OtpRequest(val title: String, val message: String, val pendingAction: String)

/**
 * State and actions behind the "Test" tab (sections A–D). Things to know:
 * - card number, CVV and OTP come from the Paywall secure fields (the SDK accepts nothing else);
 * - a 3D URL can be opened in a WebView;
 * - Masterpass terms consent is needed for "Kart Kaydet" and "Kartımı Kaydet ve Öde".
 */
class HarnessState(private val inputs: SecureInputs, private val saved: PersistedFields) {

    /** SDK calls outlive screens: switching tabs must not cancel a running payment. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Form fields are saved on the device, so they survive restarts and "Yenile".
    // The environment is intentionally not saved: every launch starts on DEV.
    var environment by mutableStateOf(PaywallEnvironment.DEV)
    var accessToken by saved.text("accessToken", "")
    var includeMasterpassSession by saved.flag("includeMasterpassSession", true)
    var userPhone by saved.text("userPhone", "")
    var userId by saved.text("userId", "")
    private val trackingCode = "TRACK-${System.currentTimeMillis()}"

    val sdkInit = ActionState()
    val providerInit = ActionState()
    var sessionId by mutableStateOf<String?>(null)

    val cardList = ActionState()
    val merchantLink = ActionState()
    val cardDelete = ActionState()
    val addCard = ActionState()
    val cards = mutableStateListOf<CardRow>()
    var cardToDelete by mutableStateOf<CardRow?>(null)
    var linkedState by mutableStateOf(false)

    val registeredPayment = ActionState()
    val manualPayment = ActionState()
    var selectedCardIndex by mutableStateOf(-1)
    var paymentAmount by saved.text("paymentAmount", "100")
    var successUrl by saved.text("successUrl", "https://merchant.com/success")
    var failUrl by saved.text("failUrl", "https://merchant.com/fail")
    var customerFullName by saved.text("customerFullName", "Test User")
    var customerPhone by saved.text("customerPhone", "")
    var customerEmail by saved.text("customerEmail", "test@example.com")
    var customerIdentityNumber by saved.text("customerIdentityNumber", "12345678901")
    val products = mutableStateListOf(ProductRow("TEST-PRODUCT-001", "Test Product", "1", "100", "0"))
    var cardHolderName by saved.text("cardHolderName", "")
    var expiryMonth by saved.text("expiryMonth", "12")
    var expiryYear by saved.text("expiryYear", "26")
    var cardAlias by saved.text("cardAlias", "")
    var saveCardOnPayment by saved.flag("saveCardOnPayment", false)

    val otpVerify = ActionState()
    var otpRequest by mutableStateOf<OtpRequest?>(null)
    var otpResendMessage by mutableStateOf<String?>(null)
    var otpResendLoading by mutableStateOf(false)
    private var awaitingOtp by mutableStateOf(false)
    private var pendingAction by mutableStateOf<String?>(null)

    /** 3D Secure URL waiting to be opened in the WebView. */
    var threeDUrl by mutableStateOf<String?>(null)
    var threeDOpen by mutableStateOf(false)
    var threeDSuccessUrl: String? = null
    var threeDFailUrl: String? = null

    val logs = mutableStateListOf<FlowStep>()

    val selectedCard: CardRow? get() = cards.getOrNull(selectedCardIndex)

    val isProviderAndSessionReady: Boolean get() = providerInit.success && sessionId != null

    val productTotal: BigDecimal get() = products.fold(BigDecimal.ZERO) { sum, row -> sum + row.total }

    val currentState: JsonObject
        get() = SdkJson.obj(
            "currentToken" to SdkJson.maskToken(accessToken.ifBlank { null }),
            "sessionId" to sessionId,
            "providerInitialized" to providerInit.success,
            "linkedState" to linkedState,
            "cardCount" to cards.size,
            "environment" to environment.wireValue,
            "userId" to userId.ifBlank { null },
            "userPhone" to userPhone.ifBlank { null },
            "awaitingOtp" to awaitingOtp,
            "pendingAction" to pendingAction,
        )

    fun close() = scope.cancel()

    /**
     * "Yenile": starts the flow over. Running calls are stopped; statuses, session, cards, OTP / 3D steps, logs
     * and the secure fields are cleared. Saved form fields stay unless [clearSavedFields] is set. Card number and
     * CVV are never saved (they live only in the secure fields). The SDK state is replaced on the next
     * "Init Paywall SDK".
     */
    fun refresh(clearSavedFields: Boolean) {
        scope.coroutineContext[Job]?.cancelChildren()
        listOf(
            sdkInit, providerInit, cardList, merchantLink, cardDelete, addCard,
            registeredPayment, manualPayment, otpVerify,
        ).forEach {
            it.reset()
            it.expanded = false
        }
        sessionId = null
        cards.clear()
        cardToDelete = null
        linkedState = false
        selectedCardIndex = -1
        otpRequest = null
        otpResendMessage = null
        otpResendLoading = false
        awaitingOtp = false
        pendingAction = null
        threeDUrl = null
        threeDOpen = false
        logs.clear()
        inputs.cardNumber.clear()
        inputs.cvv.clear()
        inputs.otp.clear()
        if (clearSavedFields) {
            saved.clearAll()
            environment = PaywallEnvironment.DEV
            products.clear()
            products += ProductRow("TEST-PRODUCT-001", "Test Product", "1", "100", "0")
        }
    }

    fun initPaywallSdk(context: Context) {
        if (accessToken.isBlank()) {
            sdkInit.error = "Token is required"
            return
        }
        launch(sdkInit) {
            val response = record(
                sdkInit,
                "PaywallSdk.initPaywallSdk",
                CodeSamples.initPaywallSdk(environment, includeMasterpassSession),
                SdkJson.obj(
                    "environment" to environment.wireValue,
                    "includeMasterpassSession" to includeMasterpassSession,
                    "token" to SdkJson.maskToken(accessToken),
                ),
            ) { PaywallSdk.initPaywallSdk(context, environment, accessToken.trim(), includeMasterpassSession) }
            val data = response.data
            if (response.success && data?.sdkInitialized == true) {
                sdkInit.success = true
                val masterpass = data.body?.masterpass
                if (data.hasMasterpassSession && masterpass != null) {
                    // Session info comes from the token; fill the form with it.
                    sessionId = masterpass.sessionId
                    masterpass.userId?.let { userId = it }
                    masterpass.userPhone?.let { userPhone = it }
                    // The provider is started automatically once the SDK is initialized.
                    runProviderInit(auto = true)
                }
            } else {
                sdkInit.error = response.message ?: "SDK initialization failed"
            }
        }
    }

    fun initMasterpassProvider() {
        if (!sdkInit.success || sessionId == null) {
            providerInit.error = "SDK and Session must be initialized first (use InitPaywallSdk)"
            return
        }
        launch(providerInit) { runProviderInit(auto = false) }
    }

    private suspend fun runProviderInit(auto: Boolean) {
        providerInit.start()
        try {
            val response = record(
                providerInit,
                "providers.masterpass.init",
                CodeSamples.providerInit(),
                SdkJson.obj("auto" to auto),
            ) { PaywallSdk.providers.masterpass.init() }
            if (response.success && response.data?.masterpassSdkInitialized == true) {
                providerInit.success = true
            } else {
                providerInit.error = response.message ?: "Provider initialization failed"
            }
        } finally {
            providerInit.loading = false
        }
    }

    fun accountAccess() {
        if (!accountGuards(cardList)) return
        launch(cardList) { runAccountAccess() }
    }

    private suspend fun runAccountAccess() {
        cardList.start()
        try {
            val request = SdkJson.obj("accountKey" to userPhone, "accountKeyType" to "Msisdn", "userId" to userId.trim())
            val response = record(
                cardList,
                "providers.masterpass.getCardList",
                CodeSamples.getCardList(userPhone, userId.trim()),
                request,
            ) {
                PaywallSdk.providers.masterpass.getCardList(
                    GetCardListParams(accountKey = userPhone, userId = userId.trim(), accountKeyType = AccountKeyType.MSISDN),
                )
            }

            if (openOtpIfRequired(response, "accountAccess")) return
            if (response.status == SdkStatus.SUCCESS) {
                cards.clear()
                response.data?.cards.orEmpty().forEach { card ->
                    cards += CardRow(
                        alias = card.cardAlias.orEmpty(),
                        masked = card.maskedCardNumber ?: "****",
                        bin = card.cardBin.orEmpty(),
                        isDefault = card.isDefaultCard == true,
                        uniqueCardNumber = card.uniqueCardNumber,
                    )
                }
                cardList.success = true
                linkedState = cards.isNotEmpty()
                if (selectedCardIndex >= cards.size) selectedCardIndex = -1
            } else {
                cardList.error = response.message ?: "Get card list failed"
            }
        } finally {
            cardList.loading = false
        }
    }

    fun merchantLink() {
        if (!accountGuards(merchantLink)) return
        launch(merchantLink) {
            val response = record(
                merchantLink,
                "providers.masterpass.linkMerchant",
                CodeSamples.linkMerchant(userPhone),
                SdkJson.obj("accountKey" to userPhone),
            ) { PaywallSdk.providers.masterpass.linkMerchant(LinkMerchantParams(accountKey = userPhone)) }

            if (openOtpIfRequired(response, "merchantLink")) return@launch
            if (response.status == SdkStatus.SUCCESS) {
                merchantLink.success = true
                linkedState = true
            } else {
                val message = response.message ?: "Merchant link failed"
                merchantLink.error = if (response.errorCode == "ACCOUNT_NOT_FOUND" || response.providerMeta?.httpStatus == 404) {
                    "Account not found (404). This may happen after deleting all cards. Error: $message"
                } else {
                    message
                }
            }
        }
    }

    fun confirmDeleteCard(card: CardRow) {
        cardToDelete = card
    }

    fun deleteCard() {
        val card = cardToDelete ?: return
        cardToDelete = null
        if (userPhone.isBlank()) {
            cardList.error = "UserPhone (accountKey) is required"
            return
        }
        if (userId.isBlank()) {
            cardDelete.error = "userId is required"
            return
        }
        launch(cardDelete) {
            val response = record(
                cardDelete,
                "providers.masterpass.deleteCard",
                CodeSamples.deleteCard(userPhone, card.alias),
                SdkJson.obj("accountKey" to userPhone, "cardAlias" to card.alias),
            ) { PaywallSdk.providers.masterpass.deleteCard(DeleteCardParams(accountKey = userPhone, cardAlias = card.alias)) }
            if (response.status == SdkStatus.SUCCESS) {
                cardDelete.success = true
                // Refresh the card list.
                runAccountAccess()
            } else {
                cardDelete.error = response.message ?: "Delete card failed"
            }
        }
    }

    /** "Kart Kaydet" button. */
    fun addCardToMasterpass() {
        if (!isProviderAndSessionReady) {
            addCard.error = "Provider and session must be ready first"
            return
        }
        if (userPhone.isBlank()) {
            addCard.error = "UserPhone (accountKey) is required"
            return
        }
        if (userId.isBlank()) {
            addCard.error = "userId is required"
            return
        }
        if (!cardFieldsFilled()) {
            addCard.error = "All card fields are required"
            return
        }
        if (cardAlias.isBlank()) {
            addCard.error = "Card Alias is required"
            return
        }
        launch(addCard) {
            val params = AddCardParams(
                userId = userId.trim(),
                accountKey = userPhone,
                accountAliasName = cardAlias.trim(),
                cardHolderName = cardHolderName,
                cardNumber = inputs.cardNumber.input,
                expiryDate = expiryMonth + expiryYear,
                cvv = inputs.cvv.cvvInput,
                termsConsent = inputs.terms.input,
                requestReferenceNumber = numericReference(),
                accountKeyType = AccountKeyType.MSISDN,
            )
            val response = record(
                addCard,
                "providers.masterpass.addCard",
                CodeSamples.addCard(
                    userId.trim(), userPhone, cardAlias.trim(), cardHolderName, params.expiryDate, params.requestReferenceNumber,
                ),
                cardRequest(
                    "userId" to userId.trim(),
                    "accountKey" to userPhone,
                    "accountAliasName" to cardAlias.trim(),
                    "requestReferenceNumber" to params.requestReferenceNumber,
                    "termsConsent" to inputs.terms.isChecked,
                ),
            ) { PaywallSdk.providers.masterpass.addCard(params) }

            if (openOtpIfRequired(response, "addCard")) return@launch
            when (response.status) {
                SdkStatus.SUCCESS -> addCard.success = true
                SdkStatus.ACTION_REQUIRED -> {
                    response.data?.redirectUrl?.let { offerThreeD(it, null, null) }
                    addCard.error = "3D Secure required. URL: ${response.data?.redirectUrl ?: "N/A"}"
                }
                SdkStatus.FAILED -> addCard.error = response.message ?: "Add card failed"
            }
        }
    }

    fun payWithRegisteredCard() {
        val action = registeredPayment
        val card = selectedCard
        when {
            !isProviderAndSessionReady -> action.error = "Provider and Session must be ready first"
            cards.isEmpty() -> action.error = "No registered cards available. Please get card list first."
            card == null -> action.error = "Please select a registered card"
            card.alias.isBlank() -> action.error = "Card alias is missing"
            card.bin.isBlank() -> action.error = "Card BIN is missing"
            sessionId == null -> action.error = "SessionId not found"
            else -> {
                val detail = paymentDetail(action) ?: return
                launch(action) {
                    val params = PaymentInitParams(
                        paymentSource = PaymentSource.REGISTERED_CARD,
                        paymentDetail = detail,
                        // For registered cards the alias is sent as ownerName.
                        card = PaymentInitCard(ownerName = card.alias, cardBin = card.bin, cardMasked = card.masked),
                        cardData = PaymentCardData(cardAlias = card.alias),
                        products = sdkProducts(),
                        sessionId = sessionId,
                        customer = customer(),
                    )
                    val response = record(
                        action,
                        "payment.init (REGISTERED_CARD)",
                        CodeSamples.paymentRegistered(
                            card.alias, card.bin, card.masked, detail.amount, detail.merchantUniqueCode,
                            detail.trackingCode, detail.successUrl, detail.failUrl,
                        ),
                        paymentRequest(detail, "paymentSource" to "REGISTERED_CARD", "cardAlias" to card.alias, "cardBin" to card.bin),
                    ) { PaywallSdk.payment.init(params) }
                    handlePayment(action, "payment.init (REGISTERED_CARD)", response, detail)
                }
            }
        }
    }

    fun payWithManualCard() {
        val action = manualPayment
        when {
            !isProviderAndSessionReady -> action.error = "Provider and Session must be ready first"
            sessionId == null -> action.error = "SessionId not found"
            userPhone.isBlank() -> action.error = "UserPhone (accountKey) is required for registerAndPurchase"
            userId.isBlank() -> action.error = "UserId is required for registerAndPurchase"
            !cardFieldsFilled() -> action.error = "All card fields are required"
            saveCardOnPayment && cardAlias.isBlank() -> action.error = "Card Alias is required when saving card"
            else -> {
                val detail = paymentDetail(action) ?: return
                if (saveCardOnPayment) registerAndPurchase(detail) else manualPaymentInit(detail)
            }
        }
    }

    private fun manualPaymentInit(detail: PaymentDetail) = launch(manualPayment) {
        val params = PaymentInitParams(
            paymentSource = PaymentSource.MANUAL_CARD,
            paymentDetail = detail,
            cardData = PaymentCardData(
                cardNumber = inputs.cardNumber.input,
                ownerName = cardHolderName,
                expiryDate = expiryMonth + expiryYear,
                cvv = inputs.cvv.cvvInput,
            ),
            products = sdkProducts(),
            sessionId = sessionId,
            customer = customer(),
        )
        val response = record(
            manualPayment,
            "payment.init (MANUAL_CARD)",
            CodeSamples.paymentManual(
                cardHolderName, expiryMonth + expiryYear, detail.amount, detail.merchantUniqueCode,
                detail.trackingCode, detail.successUrl, detail.failUrl,
            ),
            paymentRequest(detail, "paymentSource" to "MANUAL_CARD", "cardData" to cardRequest()),
        ) { PaywallSdk.payment.init(params) }
        handlePayment(manualPayment, "payment.init (MANUAL_CARD)", response, detail)
    }

    private fun registerAndPurchase(detail: PaymentDetail) = launch(manualPayment) {
        val params = RegisterAndPurchaseParams(
            accountKey = userPhone,
            merchantUserId = userId.trim(),
            paymentDetail = detail,
            cardData = RegisterCardData(
                cardNumber = inputs.cardNumber.input,
                cardHolderName = cardHolderName,
                expiryDate = expiryMonth + expiryYear,
                cvv = inputs.cvv.cvvInput,
                cardAlias = cardAlias.trim(),
            ),
            termsConsent = inputs.terms.input,
            products = sdkProducts(),
            sessionId = sessionId,
            accountKeyType = AccountKeyType.MSISDN,
            customer = customer(),
            force3D = true,
        )
        val response = record(
            manualPayment,
            "payment.registerAndPurchase",
            CodeSamples.registerAndPurchase(
                userPhone, userId.trim(), cardHolderName, expiryMonth + expiryYear, cardAlias.trim(), detail.amount,
                detail.merchantUniqueCode, detail.trackingCode, detail.successUrl, detail.failUrl,
            ),
            paymentRequest(
                detail,
                "accountKey" to userPhone,
                "accountKeyType" to "Msisdn",
                "merchantUserId" to userId.trim(),
                "force3D" to true,
                "cardData" to cardRequest("cardAlias" to cardAlias.trim()),
                "termsConsent" to inputs.terms.isChecked,
            ),
        ) { PaywallSdk.payment.registerAndPurchase(params) }
        handlePayment(manualPayment, "payment.registerAndPurchase", response, detail)
    }

    private fun handlePayment(
        action: ActionState,
        name: String,
        response: SdkResponse<MasterpassPaymentInitResult>,
        detail: PaymentDetail,
    ) {
        if (openOtpIfRequired(response, name)) return
        val data = response.data
        when {
            response.actionType == SdkActionType.THREE_D -> {
                data?.redirectUrl?.let { offerThreeD(it, detail.successUrl, detail.failUrl) }
                action.error = "3D Secure required. URL: ${data?.redirectUrl ?: "N/A"}"
            }
            response.status == SdkStatus.ACTION_REQUIRED -> action.error = response.message ?: "Action required"
            response.status == SdkStatus.SUCCESS -> action.success = true
            else -> action.error = response.message ?: "Payment failed"
        }
    }

    /** Opens the OTP dialog if [response] asks for a bank or Masterpass OTP; returns whether it did. */
    private fun openOtpIfRequired(response: SdkResponse<*>, action: String): Boolean {
        val otp = response.status == SdkStatus.ACTION_REQUIRED &&
            (response.actionType == SdkActionType.BANK_OTP || response.actionType == SdkActionType.MASTERPASS_OTP)
        if (!otp) return false
        awaitingOtp = true
        pendingAction = action
        inputs.otp.clear()
        otpResendMessage = null
        otpRequest = OtpRequest(
            title = if (response.actionType == SdkActionType.BANK_OTP) "Bank OTP Required" else "OTP Required",
            message = response.message ?: "Please enter OTP code sent to your phone",
            pendingAction = action,
        )
        return true
    }

    fun submitOtp() {
        val request = otpRequest ?: return
        otpRequest = null
        launch(otpVerify) {
            val response = record(
                otpVerify,
                "providers.masterpass.verifyOtp",
                CodeSamples.verifyOtp(),
                SdkJson.obj("otp" to "${inputs.otp.digitCount} hane (güvenli alan)", "otpIstenenIslem" to request.pendingAction),
            ) { PaywallSdk.providers.masterpass.verifyOtp(VerifyOtpParams(inputs.otp.otpInput)) }
            inputs.otp.clear()
            val data = response.data
            when {
                response.status == SdkStatus.SUCCESS -> {
                    if (pendingAction == "merchantLink") linkedState = true
                    resetOtp()
                    otpVerify.success = true
                    // Nothing is retried automatically; the user presses the original button again.
                }
                response.status == SdkStatus.ACTION_REQUIRED &&
                    (response.actionType == SdkActionType.BANK_OTP || response.actionType == SdkActionType.MASTERPASS_OTP) -> {
                    // Chained OTP (e.g. 5008 -> 5001): the dialog opens again.
                    openOtpIfRequired(response, request.pendingAction)
                }
                response.status == SdkStatus.ACTION_REQUIRED && response.actionType == SdkActionType.THREE_D -> {
                    resetOtp()
                    data?.redirectUrl?.let { offerThreeD(it, data.successUrl, data.failUrl) }
                    otpVerify.error = "3D Secure required. URL: ${data?.redirectUrl ?: "N/A"}"
                }
                else -> {
                    otpVerify.error = response.message ?: "OTP verification failed"
                    resetOtp()
                }
            }
        }
    }

    fun cancelOtp() {
        otpRequest = null
        resetOtp()
    }

    fun resendOtp() {
        otpResendMessage = null
        otpResendLoading = true
        scope.launch {
            try {
                val response = record(null, "providers.masterpass.resendOtp", CodeSamples.resendOtp(), SdkJson.obj()) {
                    PaywallSdk.providers.masterpass.resendOtp()
                }
                otpResendMessage = if (response.success) {
                    "OTP tekrar gönderildi. Lütfen yeni kodu girin."
                } else {
                    response.message ?: "OTP tekrar gönderilemedi."
                }
            } finally {
                otpResendLoading = false
            }
        }
    }

    private fun resetOtp() {
        awaitingOtp = false
        pendingAction = null
    }

    private fun offerThreeD(url: String, success: String?, fail: String?) {
        threeDUrl = url
        threeDSuccessUrl = success
        threeDFailUrl = fail
    }

    fun onThreeDFinished(result: String, lastUrl: String) {
        threeDOpen = false
        addStep("3D", error = "$result\n$lastUrl")
    }

    fun clearLogs() = logs.clear()

    fun logsAsJson(): String = SdkJson.pretty(
        com.google.gson.JsonArray().apply {
            logs.forEachIndexed { index, step ->
                add(
                    SdkJson.obj(
                        "step" to index + 1,
                        "timestamp" to step.time.toString(),
                        "durationMs" to step.endedAt - step.startedAt,
                        "actionName" to step.actionName,
                    ).apply {
                        step.request?.let { add("request", it) }
                        step.response?.let { add("response", it) }
                        step.normalized?.let { add("normalizedResult", it) }
                        step.guidance?.let { g ->
                            add("neYapilmali", SdkJson.obj("baslik" to g.title, "anlami" to g.meaning, "adimlar" to g.nextSteps))
                        }
                        step.code?.let { addProperty("merchantKodu", it) }
                        step.error?.let { addProperty("error", it) }
                    },
                )
            }
        },
    )

    fun addProduct() {
        val n = products.size + 1
        products += ProductRow("PRODUCT-$n", "New Product", "1", "0", "0")
    }

    fun removeProduct(index: Int) {
        products.removeAt(index)
        updatePaymentAmountFromProducts()
    }

    fun updatePaymentAmountFromProducts() {
        paymentAmount = productTotal.toPlainString()
    }

    private fun accountGuards(action: ActionState): Boolean {
        action.error = when {
            awaitingOtp -> "OTP verification required. Please complete OTP verification first."
            !isProviderAndSessionReady -> "Provider and session must be ready first"
            userPhone.isBlank() -> "UserPhone (accountKey) is required"
            userId.isBlank() -> "userId is required for Masterpass account access"
            else -> return true
        }
        action.success = false
        return false
    }

    private fun cardFieldsFilled(): Boolean = inputs.cardNumber.digitCount > 0 && cardHolderName.isNotBlank() &&
        expiryMonth.isNotBlank() && expiryYear.isNotBlank() && inputs.cvv.digitCount > 0

    /** Amount follows the product total when it is positive; the other fields are fixed test values. */
    private fun paymentDetail(action: ActionState): PaymentDetail? {
        if (productTotal.signum() > 0) updatePaymentAmountFromProducts()
        val amount = paymentAmount.replace(',', '.').toBigDecimalOrNull()
        if (amount == null) {
            action.error = "Amount must be a number"
            return null
        }
        return PaymentDetail(
            amount = amount,
            currencyId = 1,
            merchantUniqueCode = "MERCHANT-${System.currentTimeMillis()}",
            trackingCode = trackingCode,
            successUrl = successUrl,
            failUrl = failUrl,
            clientIp = "192.168.1.1",
            installment = 1,
        )
    }

    private fun sdkProducts(): List<Product> = products.map { row ->
        Product(productId = row.code, productName = row.name, productAmount = row.total)
    }

    private fun customer() = Customer(
        fullName = customerFullName,
        phone = customerPhone,
        email = customerEmail,
        identityNumber = customerIdentityNumber,
    )

    private fun paymentRequest(detail: PaymentDetail, vararg extra: Pair<String, Any?>): JsonObject = SdkJson.obj(
        "sessionId" to sessionId,
        *extra,
        "paymentDetail" to SdkJson.obj(
            "amount" to detail.amount,
            "currencyId" to detail.currencyId,
            "merchantUniqueCode" to detail.merchantUniqueCode,
            "trackingCode" to detail.trackingCode,
            "successUrl" to detail.successUrl,
            "failUrl" to detail.failUrl,
            "clientIp" to detail.clientIp,
            "installment" to detail.installment,
        ),
        "customer" to SdkJson.obj(
            "fullName" to customerFullName,
            "phone" to customerPhone,
            "email" to customerEmail,
            "identityNumber" to customerIdentityNumber,
        ),
        "products" to sdkProducts().map { SdkJson.obj("productId" to it.productId, "productName" to it.productName, "productAmount" to it.productAmount) },
    )

    /** Card values for the log, masked: the secure fields never expose them to the app. */
    private fun cardRequest(vararg extra: Pair<String, Any?>): JsonObject = SdkJson.obj(
        "cardNumber" to "${inputs.cardNumber.bin?.take(4) ?: "****"} **** **** **** (${inputs.cardNumber.digitCount} hane, güvenli alan)",
        "cardHolderName" to cardHolderName,
        "expiryDate" to expiryMonth + expiryYear,
        "cvv" to "***",
        *extra,
    )

    /** One SDK call: runs it, keeps the response on the button and adds one card to the "Akış" tab. */
    private suspend fun <T> record(
        action: ActionState?,
        name: String,
        code: String,
        request: JsonObject,
        call: suspend () -> SdkResponse<T>,
    ): SdkResponse<T> {
        val startedAt = System.currentTimeMillis()
        val response = call()
        val guidance = Guide.of(name, response)
        action?.let {
            it.response = response
            it.guidance = guidance
            it.code = code
        }
        logs += FlowStep(
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            actionName = name,
            code = code,
            request = request,
            response = SdkJson.response(response),
            normalized = SdkJson.normalized(response),
            guidance = guidance,
        )
        return response
    }

    private fun addStep(action: String, error: String) {
        val now = System.currentTimeMillis()
        logs += FlowStep(startedAt = now, endedAt = now, actionName = action, error = error)
    }

    /** Runs one button's work with loading state; unexpected errors become the button's error. */
    private fun launch(action: ActionState, block: suspend () -> Unit) {
        if (action.loading) return
        action.start()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                action.error = e.message ?: "Unknown error"
                addStep("error", error = e.toString())
            } finally {
                action.loading = false
            }
        }
    }

    private companion object {
        fun numericReference(): String = buildString { repeat(12) { append(Random.nextInt(10)) } }
    }
}
