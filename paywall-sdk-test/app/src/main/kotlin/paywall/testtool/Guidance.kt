package paywall.testtool

import paywall.sdk.InitPaywallSdkResult
import paywall.sdk.SdkActionType
import paywall.sdk.SdkResponse
import paywall.sdk.SdkSource
import paywall.sdk.SdkStatus
import paywall.sdk.masterpass.GetCardListResult

enum class Tone { SUCCESS, ACTION, ERROR }

/** "Ne yapılmalı?": what an SDK response means and what the merchant app does next. */
class Guidance(val tone: Tone, val title: String, val meaning: String, val nextSteps: List<String>)

object Guide {

    /** SDK numeric codes (MASTERPASS_ERROR_MAP) -> Masterpass code names, for the descriptions below. */
    private val SDK_CODE_NAMES = mapOf(
        "4001" to "OTP_IS_NOT_VALID",
        "4002" to "ACCOUNT_NOT_LINKED_TO_MERCHANT",
        "4003" to "USER_NOT_FOUND",
        "4004" to "CARD_ALREADY_EXISTS",
        "4005" to "MERCHANT_USER_ID_ALREADY_IN_USE",
        "4006" to "FRAUD_DETECTED_DUE_TO_REPETITIVE_DELETION",
        "4007" to "INVALID_ACCOUNT_KEY_IN_TOKEN",
        "4008" to "INVALID_CARD_NUMBER",
        "4009" to "INVALID_EXPIRY_DATE",
        "4010" to "INVALID_CVV",
        "4011" to "MISSING_CARD_ALIAS",
        "4012" to "MISSING_CARD_HOLDER_NAME",
    )

    private val allCodes: Map<String, String> by lazy {
        GuideData.errorCodeGroups.flatMap { it.second }.associate { it.code to it.description }
    }

    fun of(function: String, response: SdkResponse<*>): Guidance = when (response.status) {
        SdkStatus.SUCCESS -> success(function, response)
        SdkStatus.ACTION_REQUIRED -> actionRequired(response)
        SdkStatus.FAILED -> failed(function, response)
    }

    private fun success(function: String, response: SdkResponse<*>): Guidance {
        fun ok(title: String, meaning: String, vararg next: String) = Guidance(Tone.SUCCESS, title, meaning, next.toList())
        return when {
            function.endsWith("initPaywallSdk") -> {
                val session = (response.data as? InitPaywallSdkResult)?.hasMasterpassSession == true
                if (session) {
                    ok(
                        "SDK hazır",
                        "Token Paywall'da doğrulandı; Masterpass session bilgileri (sessionId, token, telefon, userId) SDK'nın içinde.",
                        "PaywallSdk.providers.masterpass.init() çağır (test aracı bunu otomatik yapar).",
                    )
                } else {
                    ok(
                        "SDK hazır, Masterpass session yok",
                        "Token doğrulandı ama Masterpass session gelmedi.",
                        "Masterpass işlemleri için includeMasterpassSession = true ile tekrar başlat.",
                    )
                }
            }
            function.endsWith("masterpass.init") -> ok(
                "Masterpass hazır",
                "Masterpass SDK session bilgileriyle yapılandırıldı.",
                "providers.masterpass.getCardList ile kullanıcının kayıtlı kartlarını getir.",
            )
            function.endsWith("getCardList") -> {
                val count = (response.data as? GetCardListResult)?.cards?.size ?: 0
                ok(
                    "$count kart geldi",
                    "Kullanıcının Masterpass hesabı bu merchant'a bağlı.",
                    "Kartları kullanıcıya listele.",
                    "Kayıtlı kartla ödeme: seçilen kartın cardAlias, maskedCardNumber ve cardBin değerleriyle payment.init / initWithRegisteredCard.",
                    "Kart yoksa: yeni kartla ödeme (payment.init MANUAL_CARD) veya registerAndPurchase / addCard.",
                )
            }
            function.endsWith("linkMerchant") -> ok(
                "Hesap merchant'a bağlandı",
                "Kullanıcının Masterpass hesabı artık bu merchant'ta kullanılabilir.",
                "getCardList'i tekrar çağır.",
            )
            function.endsWith("verifyOtp") -> ok(
                "OTP doğrulandı",
                "Kullanıcının girdiği kod doğru.",
                "OTP bir ödemeye aitse ödeme tamamlandı; SDK sonucu Paywall'a kendisi bildirdi (payment/mark).",
                "OTP bir hesap işlemine aitse (linkMerchant, getCardList, addCard) o işlemi tekrar çağır.",
            )
            function.endsWith("resendOtp") -> ok(
                "OTP tekrar gönderildi",
                "Kullanıcıya yeni bir SMS kodu gitti.",
                "Kullanıcıdan yeni kodu al ve verifyOtp çağır.",
            )
            function.endsWith("addCard") -> ok(
                "Kart kaydedildi",
                "Kart kullanıcının Masterpass hesabına eklendi.",
                "getCardList ile listeyi yenile.",
            )
            function.endsWith("deleteCard") -> ok(
                "Kart silindi",
                "Kart kullanıcının Masterpass hesabından kaldırıldı.",
                "getCardList ile listeyi yenile.",
            )
            else -> ok(
                "Ödeme tamamlandı",
                "Masterpass ödemeyi onayladı (0000). SDK sonucu Paywall'a bildirdi (payment/mark).",
                "Kullanıcıya başarı ekranını göster.",
                "nextActionHint \"Please commit the payment.\": merchant backend'i siparişi tamamlar.",
                "data.paymentId, uniqueCode ve maskedCard raporlama için saklanabilir.",
            )
        }
    }

    private fun actionRequired(response: SdkResponse<*>): Guidance = when (response.actionType) {
        SdkActionType.BANK_OTP, SdkActionType.MASTERPASS_OTP -> Guidance(
            Tone.ACTION,
            if (response.actionType == SdkActionType.BANK_OTP) "Banka OTP'si gerekiyor" else "Masterpass OTP'si gerekiyor",
            "Kullanıcının telefonuna SMS ile tek kullanımlık kod gönderildi.",
            listOf(
                "PaywallOtpField göster; kullanıcı kodu girince verifyOtp(VerifyOtpParams(otpInput)) çağır.",
                "Kod gelmezse resendOtp() çağır.",
                "Test aracı OTP penceresini otomatik açar.",
            ),
        )
        SdkActionType.THREE_D -> Guidance(
            Tone.ACTION,
            "3D Secure doğrulaması gerekiyor",
            "Banka, ödemeyi kendi 3D sayfasında doğrulamak istiyor.",
            listOf(
                "data.redirectUrl'i WebView'da aç (Paywall dönüş adresi returnUrl olarak eklenmiş).",
                "Kullanıcı bankanın sayfasında doğrular; sonuç Paywall'a gider, merchant kesin sonucu kendi backend'inden öğrenir.",
                "successUrl / failUrl'e yönlenme, WebView'ı kapatma sinyalidir.",
            ),
        )
        SdkActionType.MERCHANT_LINK_REQUIRED -> Guidance(
            Tone.ACTION,
            "Hesap bu merchant'a bağlı değil",
            "Kullanıcının Masterpass hesabı var ama bu merchant'ta henüz kullanılmamış.",
            listOf(
                "linkMerchant(LinkMerchantParams(accountKey)) çağır.",
                "OTP isterse verifyOtp; ardından getCardList'i tekrar çağır.",
            ),
        )
        null -> Guidance(Tone.ACTION, "Aksiyon gerekiyor", response.message.orEmpty(), emptyList())
    }

    private fun failed(function: String, response: SdkResponse<*>): Guidance {
        val code = response.errorCode.orEmpty()
        val steps = mutableListOf<String>()
        val meaning: String
        val title: String
        when (response.source) {
            SdkSource.SDK -> {
                title = "İşlem başarısız ($code)"
                meaning = response.message ?: "Parametre ya da çağrı sırası hatası."
                sdkFix(code)?.let(steps::add)
                response.field?.let { steps += "Hatalı alan: $it" }
                response.actionHint?.let { steps += it }
            }
            SdkSource.PAYWALL -> {
                title = "Paywall isteği reddetti ($code)"
                meaning = response.message ?: "Paywall hata kodu: $code"
                steps += "Paywall ekibiyle errorCode ($code) üzerinden kontrol et."
            }
            SdkSource.MASTERPASS -> {
                title = "Masterpass hatası ($code)"
                val name = SDK_CODE_NAMES[code] ?: code
                val action = GuideData.errorActions.firstOrNull { it.code == name }
                meaning = action?.meaning ?: allCodes[name] ?: response.message ?: "Masterpass hata kodu: $code"
                if (action != null) steps += action.actions
                when (code) {
                    "MASTERPASS_ERROR" -> steps += "Masterpass'a ulaşılamadı ya da cevap çözülemedi; bağlantıyı kontrol edip tekrar dene."
                    "MASTERPASS_TIMEOUT" -> steps += "Masterpass zamanında cevap vermedi; ödemenin sonucu belirsiz. " +
                        "Tekrar denemeden önce ödemenin durumunu Paywall'dan sorgula."
                    "SDK_ERROR" -> steps += "Beklenmeyen hata. message alanına bak."
                }
                if (response.message != null && response.message != meaning) steps += "SDK mesajı: ${response.message}"
            }
        }
        if (function.startsWith("payment.") && response.source != SdkSource.SDK) {
            steps += "SDK ödemeyi Paywall'a başarısız olarak bildirdi (payment/mark); kullanıcıya hata göster, tekrar deneyebilirsin."
        }
        return Guidance(Tone.ERROR, title, meaning, steps)
    }

    private fun sdkFix(code: String): String? = when (code) {
        "INIT_WITH_MASTERPASS_TOKEN_ERROR", "VERIFY_RESPONSE_INVALID" ->
            "Paywall token'ı doğrulamadı. Token geçersiz ya da süresi dolmuş olabilir veya ortam (DEV / TEST / PROD) " +
                "token'ın ortamıyla aynı değil. Merchant backend'inden yeni token al. Ayrıntı Akış → ③ istekte."
        "MASTERPASS_OBJECT_NULL" ->
            "Paywall Masterpass session bilgisi dönmedi; token Masterpass session'ı içermiyor (backend'e sor)."
        "MISSING_INCLUDE_MASTERPASS_SESSION" -> "includeMasterpassSession = true ile çağır."
        "INVALID_ENVIRONMENT" -> "Geçerli bir ortam seç (DEV / TEST / PROD)."
        "SDK_NOT_INITIALIZED" -> "Önce PaywallSdk.initPaywallSdk çağır."
        "SESSION_NOT_STARTED", "MISSING_TOKEN", "INVALID_SESSION" ->
            "initPaywallSdk'yı includeMasterpassSession = true ve geçerli bir token ile çağır."
        "PROVIDER_NOT_INITIALIZED" -> "Önce PaywallSdk.providers.masterpass.init() çağır."
        "SESSION_EXPIRED" -> "Session süresi doldu: merchant backend'inden yeni token al, initPaywallSdk'yı tekrar çağır."
        "SDK_NOT_LOADED" -> "Masterpass hazırlanamadı (ör. root'lu cihaz, eksik session bilgisi)."
        "TERMS_NOT_ACCEPTED" -> "Kullanıcı Masterpass koşul onay kutusunu işaretlemeli (kart kaydı için zorunlu)."
        "MISSING_OTP_CODE", "OTP_IS_NOT_VALID" -> "Kullanıcıdan OTP'yi tekrar iste."
        else -> if (code.startsWith("MISSING_") || code.startsWith("INVALID_")) "İlgili alanı düzeltip tekrar dene." else null
    }
}
