## Tanıtım

Bu repository iki ana bileşenden oluşmaktadır:

- **public-sdk**: Paywall Android SDK'nın dağıtım paketini içerir. Merchant'lar bu klasörü indirip kendi
  projelerine kopyalar (bkz. [Kurulum](#1-kurulum)).
- **paywall-sdk-test**: SDK'nın tüm fonksiyonlarını cihazda deneyen Android test uygulamasıdır. SDK'yı
  `public-sdk` klasöründen kullanır; her çağrıda giden istekleri ve SDK'nın döndüğü cevabı gösterir. Android Studio
  ile `paywall-sdk-test` klasörünü açarak ya da `cd paywall-sdk-test && ./run.sh` komutuyla çalıştırılır.

# Paywall Android SDK

Paywall'ın Masterpass üzerinden kart saklama ve ödeme akışlarını Android uygulamalarına sunan SDK. Davranışı ve
döndüğü cevaplar Paywall Web SDK ile aynıdır. Masterpass Android SDK, SDK'nın içinde gömülü gelir; ayrıca
eklenmez.

```kotlin
implementation("paywall:paywall-android-sdk:1.0.0")
```

## İçindekiler

1. [Kurulum](#1-kurulum)
2. [Temel kavramlar](#2-temel-kavramlar)
3. [Güvenli giriş alanları](#3-güvenli-giriş-alanları)
4. [Fonksiyonlar](#4-fonksiyonlar)
   - [4.1 initPaywallSdk](#41-paywallsdkinitpaywallsdk)
   - [4.2 providers.masterpass.init](#42-providersmasterpassinit)
   - [4.3 getCardList](#43-providersmasterpassgetcardlist)
   - [4.4 linkMerchant](#44-providersmasterpasslinkmerchant)
   - [4.5 verifyOtp](#45-providersmasterpassverifyotp)
   - [4.6 resendOtp](#46-providersmasterpassresendotp)
   - [4.7 addCard](#47-providersmasterpassaddcard)
   - [4.8 deleteCard](#48-providersmasterpassdeletecard)
   - [4.9 payment.init](#49-paymentinit)
   - [4.10 payment.initWithRegisteredCard](#410-paymentinitwithregisteredcard)
   - [4.11 payment.registerAndPurchase](#411-paymentregisterandpurchase)
5. [OTP akışı](#5-otp-akışı)
6. [3D Secure akışı](#6-3d-secure-akışı)
7. [Hata kodları](#7-hata-kodları)
8. [Uçtan uca örnek](#8-uçtan-uca-örnek)
9. [Güvenlik](#9-güvenlik)

---

## 1. Kurulum

### SDK'yı projenize ekleme

SDK, `public-sdk` klasörü olarak dağıtılır. Klasörü indirip projenizin içine koyarsınız; internetten bir şey
çekilmez.

1. `public-sdk` klasörünü projenizin kök klasörüne, `settings.gradle.kts` dosyasının yanına kopyalayın:

   ```
   MerchantApp/
   ├── app/
   ├── public-sdk/
   │   └── paywall/paywall-android-sdk/
   │       ├── maven-metadata.xml
   │       └── 1.0.0/  (paywall-android-sdk-1.0.0.aar, .pom, .module ...)
   ├── settings.gradle.kts
   └── build.gradle.kts
   ```

2. `settings.gradle.kts` içinde klasörü depo olarak tanımlayın:

   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven {
               url = uri("public-sdk")
               content { includeGroup("paywall") }
           }
       }
   }
   ```

3. `app/build.gradle.kts` içine bağımlılığı ekleyin ve Gradle'ı senkronize edin (Android Studio: **Sync Now**):

   ```kotlin
   dependencies {
       implementation("paywall:paywall-android-sdk:1.0.0")
   }
   ```

Projeniz Groovy kullanıyorsa (`settings.gradle`, `app/build.gradle`) aynı satırlar şöyledir:

```groovy
maven {
    url = uri("public-sdk")
    content { includeGroup("paywall") }
}

implementation "paywall:paywall-android-sdk:1.0.0"
```

- **Klasörün tamamını kopyalayın.** `.aar` dosyasını tek başına `libs/` klasörüne koymayın. SDK'nın kullandığı
  AndroidX, Compose, OkHttp, Gson ve coroutines kütüphaneleri klasördeki `.pom` / `.module` dosyalarında tanımlıdır;
  Gradle bunları bu dosyalardan okuyup kendisi ekler. `.aar` tek başına eklenirse bu kütüphaneler gelmez ve
  uygulama çalışırken hata verir.
- **Masterpass'ı ayrıca eklemeyin.** SDK'nın içinde gelir. İkinci kez eklenirse derleme "duplicate class" hatası
  verir.
- **Yeni sürüm geldiğinde** `public-sdk` klasörünü yenisiyle değiştirin ve bağımlılıktaki sürüm numarasını
  güncelleyin (ör. `1.0.0` → `1.0.1`).

### Gereksinimler

| | |
|---|---|
| minSdk | 23 |
| compileSdk | 36 |
| Kotlin | 2.1+ (fonksiyonlar `suspend`; coroutine ile çağrılır) |
| Jetpack Compose | İsteğe bağlı; yalnızca Compose bileşenleri kullanılacaksa |

Manifest'e ve R8 / ProGuard kurallarına bir şey eklemeniz gerekmez; izinler ve kurallar SDK ile birlikte gelir.

### Çağırma şekli

Tüm fonksiyonlar `suspend`'dir ve ana thread'den güvenle çağrılabilir:

```kotlin
lifecycleScope.launch {
    val response = PaywallSdk.initPaywallSdk(...)
}
```

> Java'dan kullanım için callback'li API henüz yok; şimdilik Kotlin coroutine ile kullanılır.

---

## 2. Temel kavramlar

### Akış sırası

```
Merchant backend ─▶ Paywall'dan geçici token alır (SDK token üretmez)
        │
        ▼
1. PaywallSdk.initPaywallSdk(...)            Token doğrulanır, Masterpass session alınır
2. PaywallSdk.providers.masterpass.init()    Masterpass hazırlanır
3. Hesap / kart işlemleri                    getCardList, linkMerchant, addCard, deleteCard
4. Ödeme                                     payment.init, initWithRegisteredCard, registerAndPurchase
        │
        └─ OTP istenirse verifyOtp / resendOtp, 3D istenirse redirectUrl WebView'da açılır
```

`sessionId`, Masterpass token'ı, kullanıcı telefonu ve `userId` token'ın içinden gelir ve SDK'da tutulur. Bunları
sonraki çağrılara siz vermezsiniz; SDK kendisi kullanır.

### Her fonksiyon aynı cevabı döner: `SdkResponse<T>`

SDK **hiçbir zaman exception fırlatmaz**. Her durumda bir `SdkResponse` döner:

| Alan | Anlamı |
|---|---|
| `status` | `SUCCESS`, `ACTION_REQUIRED` veya `FAILED` |
| `success` | `FAILED` dışında `true`. **Dikkat:** `ACTION_REQUIRED`'da da `true`'dur; akışı `status`'a göre kurun. |
| `source` | Sonucu üreten katman: `SDK` (SDK içinde, ör. parametre kontrolü), `PAYWALL`, `MASTERPASS` |
| `message` | Okunabilir açıklama |
| `errorCode` | Yalnızca `FAILED`'da dolu. Bkz. [Hata kodları](#7-hata-kodları) |
| `actionType` | Yalnızca `ACTION_REQUIRED`'da dolu: `BANK_OTP`, `MASTERPASS_OTP`, `THREE_D` (JSON'da `"3D"`), `MERCHANT_LINK_REQUIRED` |
| `data` | Fonksiyona özel sonuç (kartlar, `redirectUrl`, `paymentId`...) |
| `providerMeta` | Teknik bilgi; çoğu cevapta `data.providerMeta` içindedir. Bkz. [providerMeta](#providermeta) |
| `field` | Validasyon hatasında hatalı alanın adı |
| `actionHint` / `nextActionHint` | Sıradaki adım için ipucu |

Bu dokümandaki örnek cevaplar JSON olarak gösterilmiştir. Kotlin'de aynı alanlar `SdkResponse` nesnesinin
property'leridir; `status`, `source` ve `actionType` enum'dur (JSON'daki değerleri `wireValue`). Örneklerde `null`
alanlar ve `providerMeta.raw` kısalık için yazılmamıştır. Tam bir cevap şöyle görünür:

```json
{
  "success": false,
  "status": "FAILED",
  "source": "SDK",
  "message": "Card holder name is missing. Please provide the name on the card.",
  "errorCode": "MISSING_CARD_HOLDER_NAME",
  "actionType": null,
  "data": null,
  "providerMeta": null,
  "actionHint": null,
  "field": null,
  "nextActionHint": null
}
```

### `providerMeta`

Sağlayıcının (Paywall / Masterpass) teknik bilgisi. Kullanıcıya gösterilmez; hata ayıklama ve kayıt içindir.

| Alan | Anlamı |
|---|---|
| `httpStatus` | HTTP durum kodu |
| `responseCode` | Sağlayıcının cevap kodu (ör. Masterpass `0000` başarılı, `5008` OTP, `5010` 3D) |
| `raw` | Paywall / Masterpass'ın ham cevabı (JSON metni) |

Nerede bulunur (web SDK ile aynı):

- `data` dolu olan her cevapta `data.providerMeta` içindedir.
- Ön kontrol hatalarında (`SDK_NOT_INITIALIZED`, `PROVIDER_NOT_INITIALIZED` vb.) ayrıca `response.providerMeta`'dadır.
- `data`'sı olmayan hatalarda (ör. Masterpass'ın reddettiği ödeme) yalnızca `response.providerMeta`'dadır.

Her durumda okumak için:

```kotlin
val providerMeta = response.data?.providerMeta ?: response.providerMeta
```

`raw` her zaman dolmaz:

- `payment.init` ve `initWithRegisteredCard`: her zaman.
- `registerAndPurchase`: Masterpass hata döndürdüğünde her zaman, diğer sonuçlarda yalnızca DEBUG'da.
- Diğer fonksiyonlar: yalnızca `initPaywallSdk(..., logLevel = PaywallLogLevel.DEBUG)` ile.

### Cevabı işleme

Her cevap için önerilen işleme şekli:

```kotlin
when (response.status) {
    SdkStatus.SUCCESS -> {
        // İşlem tamam: response.data
    }
    SdkStatus.ACTION_REQUIRED -> when (response.actionType) {
        SdkActionType.BANK_OTP,
        SdkActionType.MASTERPASS_OTP -> showOtpScreen()          // sonra verifyOtp(...)
        SdkActionType.THREE_D -> openWebView(redirectUrl)         // bkz. 3D Secure akışı
        SdkActionType.MERCHANT_LINK_REQUIRED -> linkMerchant()    // sonra getCardList(...)
        null -> Unit
    }
    SdkStatus.FAILED -> showError(response.errorCode, response.message)
}
```

### Ortamlar

`PaywallEnvironment.DEV`, `TEST`, `PROD`. Paywall adresleri SDK içinde çözülür; Masterpass'ın hangi ortamına
gidileceği token'daki session bilgisinden belirlenir.

### Diğer kurallar

- Çağrılar SDK içinde sırayla çalışır; aynı anda iki çağrı yapılırsa ikincisi birincinin bitmesini bekler.
- Token ve session yalnızca bellekte tutulur. Uygulama süreci kapanırsa `initPaywallSdk` yeniden çağrılmalıdır.
- `initPaywallSdk` tekrar çağrılırsa SDK'nın tüm durumu sıfırlanır; `providers.masterpass.init()` da tekrar
  çağrılmalıdır.

---

## 3. Güvenli giriş alanları

Kart numarası, CVV, OTP ve Masterpass koşul onayı yalnızca Paywall'ın güvenli alanlarıyla alınır. Değerler
Masterpass'ın güvenli alanında kalır: uygulamanız ve Paywall bunları **okuyamaz**; Masterpass şifreleyip gönderir.
Fonksiyonlara alanın değeri değil, alanın `input` nesnesi verilir.

| Alan | View | Compose | Fonksiyona verilen |
|---|---|---|---|
| Kart numarası | `PaywallCardNumberField` | `PaywallCardNumberField` + `rememberPaywallCardNumberState()` | `input` (`CardNumberInput`) |
| CVV | `PaywallCvvField` | `PaywallCvvField` + `rememberPaywallCvvState()` | View: `input`, Compose: `cvvInput` (`CvvInput`) |
| OTP | `PaywallOtpField` | `PaywallOtpField` + `rememberPaywallOtpState()` | View: `input`, Compose: `otpInput` (`OtpInput`) |
| Koşul onayı | `PaywallTermsCheckBox` | `PaywallTermsCheckBox` + `rememberPaywallTermsState()` | `input` (`TermsConsentInput`) |

Alanlardan okunabilenler: kart alanında `bin` (ilk 8 hane; 8 haneden azsa `null`), tüm metin alanlarında
`digitCount` ve `isValid`, koşul kutusunda `isChecked`. Değerin kendisi okunamaz. Alanı boşaltmak için `clear()`.

### View (XML)

```xml
<paywall.sdk.ui.PaywallCardNumberField
    android:id="@+id/cardNumber"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="Kart numarası"
    android:textSize="18sp"
    android:background="@drawable/input_bg"
    android:padding="16dp" />

<paywall.sdk.ui.PaywallCvvField
    android:id="@+id/cvv"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="CVV" />

<paywall.sdk.ui.PaywallTermsCheckBox
    android:id="@+id/terms"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Masterpass kullanım koşullarını kabul ediyorum" />
```

```kotlin
val cardNumber = findViewById<PaywallCardNumberField>(R.id.cardNumber)
cardNumber.setOnBinChangedListener { bin -> /* ilk 8 hane ya da null: kart tipi / taksit için */ }
cardNumber.setOnInputChangedListener { /* ör. cardNumber.digitCount ile buton aktifliği */ }
cardNumber.editText.typeface = myTypeface   // stil için; metin okunamaz
```

### Compose

```kotlin
val cardState = rememberPaywallCardNumberState()
val cvvState = rememberPaywallCvvState()
val termsState = rememberPaywallTermsState()

PaywallCardNumberField(
    state = cardState,
    modifier = Modifier.fillMaxWidth(),
    textStyle = MaterialTheme.typography.bodyLarge,
    decorationBox = { inner ->
        Box(Modifier.border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).padding(16.dp)) { inner() }
    },
)
PaywallCvvField(state = cvvState)
PaywallTermsCheckBox(state = termsState) { Text("Masterpass kullanım koşullarını kabul ediyorum") }

// cardState.bin, cardState.digitCount, cardState.isValid → Compose state'tir, değişince ekran yenilenir
```

### Stil

- Görünüm tamamen merchant'a aittir: View'da XML öznitelikleri ve `editText`, Compose'da `modifier`, `textStyle`,
  `cursorBrush`, `decorationBox`; koşul kutusunda `colors`, `labelAtStart` ve `label`.
- Kart alanı numarayı 4'lü gruplar halinde gösterir ve klavyesi her zaman sayısaldır (`android:inputType` bu alanda
  dikkate alınmaz).
- Koşul kutusu yalnızca kullanıcının dokunuşuyla işaretlenir; programla işaretlenemez.

---

## 4. Fonksiyonlar

Her fonksiyonun altında olası sonuçlar ve ne yapılacağı var. Çağrı sırası yanlışsa (ör. `initPaywallSdk`
yapılmadan) her fonksiyon `FAILED` ve `SDK_NOT_INITIALIZED`, `SESSION_NOT_STARTED`, `PROVIDER_NOT_INITIALIZED`,
`SESSION_EXPIRED` gibi kodlar döner; bunlar tablolarda tekrar edilmedi ([Hata kodları](#7-hata-kodları)).

### 4.1 `PaywallSdk.initPaywallSdk`

SDK'yı başlatır. Merchant backend'inin Paywall'dan aldığı geçici token Paywall'da doğrulanır ve Masterpass session
bilgileri SDK'ya alınır.

```kotlin
val response = PaywallSdk.initPaywallSdk(
    context = context,
    environment = PaywallEnvironment.TEST,
    token = tokenFromMerchantBackend,
    includeMasterpassSession = true,
)
```

| Parametre | Açıklama |
|---|---|
| `context` | Herhangi bir context; yalnızca application context saklanır |
| `environment` | `DEV`, `TEST`, `PROD` |
| `token` | Merchant backend'inin Paywall'dan aldığı geçici token |
| `includeMasterpassSession` | Masterpass işlemleri için `true` |
| `logLevel` | İsteğe bağlı, varsayılan `PaywallLogLevel.ERROR`. `DEBUG`'da `providerMeta.raw` tüm fonksiyonlarda dolar |

`data`: `InitPaywallSdkResult` → `environment`, `sdkInitialized`, `hasMasterpassSession`, `body.masterpass`
(`sessionId`, `userId`, `userPhone`, `masterpassMerchantId`, `sessionExpiryDate`, `force3D`...).

Örnek cevap:

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "SDK",
  "message": "SDK initialized with Masterpass token verify successfully",
  "data": {
    "environment": "test",
    "sdkInitialized": true,
    "hasMasterpassSession": true,
    "body": {
      "tempTokenId": 123456,
      "expiryDateTime": "2026-10-19T14:30:00",
      "masterpass": {
        "sessionId": "8f6d1c2e-...",
        "sessionExpiryDate": "2026-10-19T14:30:00",
        "masterpassMerchantId": "34700001",
        "masterpassTerminalGroupId": "...",
        "userId": "MERCHANT_USER_ID",
        "userPhone": "905551234567",
        "force3D": false
      }
    }
  }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | SDK hazır | `providers.masterpass.init()` çağır |
| `FAILED` · `MISSING_TOKEN` | Token boş | Token'ı ver |
| `FAILED` · `INIT_WITH_MASTERPASS_TOKEN_ERROR` | Paywall token'ı reddetti (ör. HTTP 401) | Token geçersiz / süresi dolmuş ya da ortam yanlış; yeni token al |
| `FAILED` · `VERIFY_RESPONSE_INVALID`, `MASTERPASS_OBJECT_NULL` | Paywall cevabında Masterpass session yok | Backend'in token'ı Masterpass session'lı ürettiğini kontrol et |

### 4.2 `providers.masterpass.init`

Masterpass'ı session bilgileriyle hazırlar. Parametre almaz. `initPaywallSdk`'dan sonra, diğer fonksiyonlardan önce
bir kez çağrılır.

```kotlin
val response = PaywallSdk.providers.masterpass.init()
```

Örnek cevap:

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "SDK",
  "message": "Masterpass provider initialized successfully",
  "data": { "masterpassSdkInitialized": true }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` (`data.masterpassSdkInitialized = true`) | Masterpass hazır | `getCardList` ile kartları getir |
| `FAILED` · `MISSING_MERCHANT_ID` | Session'da Masterpass merchant ID yok | Backend'e sor |
| `FAILED` · `MASTERPASS_PROVIDER_INIT_ERROR` | Masterpass hazırlanamadı (ör. root'lu cihaz) | Kullanıcıya hata göster |

### 4.3 `providers.masterpass.getCardList`

Kullanıcının Masterpass'ta kayıtlı kartlarını getirir.

```kotlin
val response = PaywallSdk.providers.masterpass.getCardList(
    GetCardListParams(
        accountKey = "905551234567",            // kullanıcının telefonu
        userId = "MERCHANT_USER_ID",
        accountKeyType = AccountKeyType.MSISDN, // varsayılan
    ),
)
```

`data`: `GetCardListResult` → `cards` (`MasterpassCard` listesi: `cardAlias`, `maskedCardNumber`, `cardBin`,
`cardType`, `productName`, `isDefaultCard`, `isExpired`, `expireSoon`...), `accountInformation`, `token`.

Örnek cevap (kartlar geldi):

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "MASTERPASS",
  "message": "Cards fetched successfully",
  "data": {
    "cards": [
      {
        "cardAlias": "Maaş kartım",
        "maskedCardNumber": "545616******3487",
        "cardBin": "54561600",
        "cardType": "MASTER",
        "productName": "World",
        "isDefaultCard": true,
        "isExpired": false,
        "expireSoon": false
      }
    ],
    "accountInformation": { "isAccountLinked": true },
    "providerMeta": { "httpStatus": 200, "responseCode": "0000" }
  }
}
```

Örnek cevap (hesap bu merchant'a bağlı değil):

```json
{
  "success": true,
  "status": "ACTION_REQUIRED",
  "source": "MASTERPASS",
  "message": "Account is not linked to merchant",
  "actionType": "MERCHANT_LINK_REQUIRED",
  "data": { "providerMeta": { "httpStatus": 404, "responseCode": "ACCOUNT_NOT_LINKED_TO_MERCHANT" } }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | Kartlar geldi (liste boş olabilir) | Kartları listele; ödemede kartın `cardAlias`, `maskedCardNumber`, `cardBin`'i kullanılır |
| `ACTION_REQUIRED` · `MERCHANT_LINK_REQUIRED` | Masterpass hesabı var, bu merchant'a bağlı değil | `linkMerchant` çağır, sonra tekrar `getCardList` |
| `ACTION_REQUIRED` · `MASTERPASS_OTP` | Masterpass OTP istiyor | [OTP akışı](#5-otp-akışı), sonra tekrar `getCardList` |
| `FAILED` · `ACCOUNT_NOT_FOUND` | Kullanıcının Masterpass hesabı yok | Yeni kartla ödeme; kart kaydı (`addCard` / `registerAndPurchase`) hesabı oluşturur |
| `FAILED` · `USER_NOT_FOUND` | Kullanıcı bulunamadı | Kullanıcı bilgilerini kontrol et |
| `FAILED` · `MISSING_USER_ID`, `MISSING_ACCOUNT_KEY` | Parametre eksik | Parametreyi ver |

### 4.4 `providers.masterpass.linkMerchant`

Kullanıcının Masterpass hesabını bu merchant'a bağlar.

```kotlin
val response = PaywallSdk.providers.masterpass.linkMerchant(
    LinkMerchantParams(accountKey = "905551234567"),
)
```

`data`: `LinkMerchantResult` → `isAccountLinked`, `cards`, `accountInformation`, `token`.

Örnek cevap (OTP gerekiyor):

```json
{
  "success": true,
  "status": "ACTION_REQUIRED",
  "source": "MASTERPASS",
  "message": "Masterpass OTP verification is required. Please enter the OTP code sent by Masterpass.",
  "actionType": "MASTERPASS_OTP",
  "data": { "token": "a1b2c3...9f8e", "providerMeta": { "httpStatus": 202, "responseCode": "5008" } }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | Hesap bağlandı | `getCardList`'i tekrar çağır |
| `ACTION_REQUIRED` · `MASTERPASS_OTP` / `BANK_OTP` | OTP gerekiyor | [OTP akışı](#5-otp-akışı), sonra `getCardList` |
| `FAILED` | Masterpass hatası | `errorCode` / `message`'ı göster |

### 4.5 `providers.masterpass.verifyOtp`

Kullanıcının girdiği OTP'yi doğrular. Kod Paywall OTP alanından gelir.

```kotlin
val response = PaywallSdk.providers.masterpass.verifyOtp(
    VerifyOtpParams(otp = otpField.input),   // Compose: otpState.otpInput
)
```

`data`: `VerifyOtpResult` → `isVerified`, `token`, `maskedCard`, `retrievalReferenceNumber`; 3D'de `redirectUrl`;
OTP bir ödemeye aitse `paymentId`, `masterpassPaymentId`, `uniqueCode`, `successUrl`, `failUrl`...

Örnek cevap (OTP doğru):

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "MASTERPASS",
  "message": "OTP verified successfully",
  "data": { "isVerified": true, "token": "d4e5f6...1a2b", "retrievalReferenceNumber": "200003642398", "providerMeta": { "httpStatus": 200, "responseCode": "0000" } }
}
```

Örnek cevap (ödeme OTP'sinden sonra 3D gerekiyor). `redirectUrl`'in sonundaki `returnUrl`'i SDK ekler:

```json
{
  "success": true,
  "status": "ACTION_REQUIRED",
  "source": "MASTERPASS",
  "message": "3D Secure verification is required. You will be redirected to the 3D Secure screen.",
  "actionType": "3D",
  "data": {
    "isVerified": false,
    "retrievalReferenceNumber": "200003642398",
    "redirectUrl": "https://acs.bank.com/3d?token=...&returnUrl=https://payment-agent.paywall.com/masterpass/vposcallback?uniqueCode=8dd69b76-...",
    "sessionId": "8f6d1c2e-...",
    "successUrl": "https://merchant.com/success",
    "failUrl": "https://merchant.com/fail",
    "paymentId": 123456,
    "uniqueCode": "8dd69b76-...",
    "merchantUniqueCode": "MERCHANT-ORDER-123",
    "providerMeta": { "httpStatus": 202, "responseCode": "5010" }
  }
}
```

Örnek cevap (yanlış kod):

```json
{
  "success": false,
  "status": "FAILED",
  "source": "MASTERPASS",
  "message": "OTP code is not valid",
  "errorCode": "OTP_IS_NOT_VALID",
  "data": { "isVerified": false, "providerMeta": { "httpStatus": 400, "responseCode": "OTP_IS_NOT_VALID" } },
  "field": "otp"
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | OTP doğru | OTP bir ödemeye aitse ödeme tamamlandı (SDK sonucu Paywall'a kendisi bildirir). Hesap işlemine aitse o işlemi tekrar çağır. |
| `ACTION_REQUIRED` · `BANK_OTP` / `MASTERPASS_OTP` | İkinci bir OTP gerekiyor (ör. önce Masterpass, sonra banka) | OTP ekranını tekrar göster |
| `ACTION_REQUIRED` · `THREE_D` | OTP'den sonra 3D gerekiyor | `data.redirectUrl`'i aç ([3D akışı](#6-3d-secure-akışı)) |
| `FAILED` · `OTP_IS_NOT_VALID` | Yanlış kod | Kullanıcı tekrar denesin; ödeme beklemede kalır |
| `FAILED` · `MASTERPASS_TIMEOUT` | Masterpass zamanında cevap vermedi | Tekrar denenebilir; ödeme beklemede kalır |
| `FAILED` · `MISSING_OTP_CODE` | Kod girilmedi | Kod iste |

### 4.6 `providers.masterpass.resendOtp`

Bekleyen OTP'yi yeniden gönderir. Parametre almaz. Geri sayımı (ör. "60 sn sonra tekrar gönder") merchant yönetir.

```kotlin
val response = PaywallSdk.providers.masterpass.resendOtp()
```

Örnek cevap:

```json
{
  "success": true,
  "status": "ACTION_REQUIRED",
  "source": "MASTERPASS",
  "message": "OTP resent. Masterpass OTP verification is required. Please enter the OTP code sent by Masterpass.",
  "actionType": "MASTERPASS_OTP",
  "data": { "token": "a1b2c3...9f8e", "providerMeta": { "httpStatus": 202, "responseCode": "5008" } }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` / `ACTION_REQUIRED` (OTP) | Yeni kod gönderildi | Kullanıcıdan yeni kodu al, `verifyOtp` |
| `FAILED` · `TOKEN_ALREADY_USED` | Bekleyen bir OTP yok | Önce OTP isteyen işlemi başlat |

### 4.7 `providers.masterpass.addCard`

Kartı kullanıcının Masterpass hesabına kaydeder (ödeme yapmadan).

```kotlin
val response = PaywallSdk.providers.masterpass.addCard(
    AddCardParams(
        userId = "MERCHANT_USER_ID",
        accountKey = "905551234567",
        accountAliasName = "Maaş kartım",
        cardHolderName = "Ad Soyad",
        cardNumber = cardNumberField.input,
        expiryDate = "1228",                  // MMYY
        cvv = cvvField.input,                 // Compose: cvvState.cvvInput
        termsConsent = termsCheckBox.input,   // kullanıcı işaretlemiş olmalı
        requestReferenceNumber = "123456789012",
    ),
)
```

`data`: `AddCardResult` → `cardAlias`, `token`, `retrievalReferenceNumber`, 3D'de `redirectUrl`. `maskedCard` kart
eklemede `null`'dır; maskeli numara sonraki `getCardList`'te gelir.

Örnek cevap (kart kaydedildi):

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "MASTERPASS",
  "message": "Card added successfully.",
  "data": { "sessionId": "8f6d1c2e-...", "cardAlias": "Maaş kartım", "providerMeta": { "httpStatus": 200, "responseCode": "0000" } }
}
```

Örnek cevap (kart zaten kayıtlı):

```json
{
  "success": false,
  "status": "FAILED",
  "source": "MASTERPASS",
  "message": "This card is already registered. Please try adding a different card.",
  "errorCode": "4004",
  "data": { "sessionId": "8f6d1c2e-...", "providerMeta": { "httpStatus": 400, "responseCode": "4004" } }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | Kart kaydedildi | `getCardList` ile listeyi yenile |
| `ACTION_REQUIRED` · `BANK_OTP` / `MASTERPASS_OTP` | OTP gerekiyor | [OTP akışı](#5-otp-akışı) |
| `ACTION_REQUIRED` · `THREE_D` | 3D gerekiyor | `data.redirectUrl`'i aç |
| `FAILED` · `TERMS_NOT_ACCEPTED` | Koşul kutusu işaretli değil | Kullanıcı işaretlesin |
| `FAILED` · `4004` (`CARD_ALREADY_EXISTS`) | Kart zaten kayıtlı | Kayıtlı kartla devam et |
| `FAILED` · `MISSING_*`, `INVALID_*_FORMAT` | Alan eksik / hatalı (`field`'a bak) | İlgili alanı düzelt |

### 4.8 `providers.masterpass.deleteCard`

Kayıtlı kartı siler.

```kotlin
val response = PaywallSdk.providers.masterpass.deleteCard(
    DeleteCardParams(accountKey = "905551234567", cardAlias = card.cardAlias!!),
)
```

Örnek cevap:

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "MASTERPASS",
  "message": "Card deleted successfully",
  "data": { "success": true, "providerMeta": { "httpStatus": 200, "responseCode": "0000" } }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | Kart silindi | `getCardList` ile listeyi yenile |
| `FAILED` · `MISSING_CARD_ALIAS` | Kart adı verilmedi | `cardAlias`'ı ver |
| `FAILED` · `4006` | Art arda çok silme (fraud koruması) | Kullanıcıyı uyar, bir süre beklensin |
| `FAILED` · `DELETE_FAILED` / Masterpass kodu | Silinemedi | `message`'ı göster |

### 4.9 `payment.init`

Yeni kartla (`MANUAL_CARD`) veya kayıtlı kartla (`REGISTERED_CARD`) ödeme. SDK sırasıyla: parametreleri kontrol
eder → Paywall'da ödeme kaydını açar → ödemeyi Masterpass'ta başlatır → sonucu Paywall'a bildirir.

**Ortak parametreler:**

```kotlin
val paymentDetail = PaymentDetail(
    amount = BigDecimal("100.00"),
    currencyId = 1,                              // TRY. Para birimi listesi: https://developer.paywall.one/sistem-verileri/para-birimleri
    merchantUniqueCode = "MERCHANT-ORDER-123",   // her ödemede benzersiz
    trackingCode = "TRACK-123",
    successUrl = "https://merchant.com/success",
    failUrl = "https://merchant.com/fail",
    clientIp = "203.0.113.10",
    installment = 1,
)
val products = listOf(
    Product(productId = "P-1", productName = "Ürün", productAmount = BigDecimal("100.00")),
)
```

**Yeni kartla:**

```kotlin
val response = PaywallSdk.payment.init(
    PaymentInitParams(
        paymentSource = PaymentSource.MANUAL_CARD,
        paymentDetail = paymentDetail,
        cardData = PaymentCardData(
            cardNumber = cardNumberField.input,
            cardHolderName = "Ad Soyad",
            expiryDate = "1228",              // MMYY
            cvv = cvvField.input,
        ),
        products = products,
        customer = Customer(fullName = "Ad Soyad", email = "ad@ornek.com"),
    ),
)
```

**Kayıtlı kartla** (`getCardList`'ten gelen `card`):

```kotlin
val response = PaywallSdk.payment.init(
    PaymentInitParams(
        paymentSource = PaymentSource.REGISTERED_CARD,
        paymentDetail = paymentDetail,
        card = PaymentInitCard(
            cardBin = card.cardBin,
            cardMasked = card.maskedCardNumber,
            ownerName = card.cardAlias,
        ),
        cardData = PaymentCardData(cardAlias = card.cardAlias),
        products = products,
    ),
)
```

| Parametre | Açıklama |
|---|---|
| `paymentSource` | `MANUAL_CARD` (yeni kart) veya `REGISTERED_CARD` (kayıtlı kart) |
| `paymentDetail` | Tutar, para birimi (`currencyId`, ör. `1` TRY; [para birimi listesi](https://developer.paywall.one/sistem-verileri/para-birimleri)), merchant kodları, dönüş URL'leri, IP, taksit; isteğe bağlı `channelId`, `tagId` |
| `cardData` | Masterpass'a giden kart verisi (Paywall'a gitmez). Yeni kartta kart numarası, son kullanma (MMYY), CVV ve kart sahibi zorunlu; kayıtlı kartta `cardAlias` zorunlu |
| `card` | Paywall'a giden maskeli bilgi. Yeni kartta boş bırakılabilir: BIN kart alanından alınır |
| `products` | Ürün listesi |
| `customer` | İsteğe bağlı müşteri bilgileri (`fullName`, `phone`, `email`, `customerId`, `identityNumber`) |
| `sessionId` | Boş bırakılır; SDK session'dan alır |
| `force3D` | `null` ise session ayarı kullanılır |
| `saveCard` | Kartı kaydetmez. Kaydederek ödeme için `registerAndPurchase` kullanılır |

`data`: `MasterpassPaymentInitResult` → `sessionId`, `status`, `actionType`, `message`, `nextActionHint`, `token`,
`retrievalReferenceNumber`, `redirectUrl`, `successUrl`, `failUrl`, `paymentId`, `masterpassPaymentId`,
`activityId`, `uniqueCode`, `merchantUniqueCode`, `maskedCard` (`bin`, `last4`, `masked`).

Örnek cevap (ödeme tamamlandı):

```json
{
  "success": true,
  "status": "SUCCESS",
  "source": "PAYWALL",
  "message": "Payment completed successfully.",
  "data": {
    "sessionId": "8f6d1c2e-...",
    "status": "SUCCESS",
    "message": "Payment completed successfully.",
    "nextActionHint": "Please commit the payment.",
    "retrievalReferenceNumber": "200003642398",
    "successUrl": "https://merchant.com/success",
    "failUrl": "https://merchant.com/fail",
    "paymentId": 123456,
    "masterpassPaymentId": "MP-987654",
    "activityId": 654321,
    "uniqueCode": "8dd69b76-...",
    "merchantUniqueCode": "MERCHANT-ORDER-123",
    "maskedCard": { "bin": "54561600", "last4": "3487", "masked": "54561600****3487" },
    "providerMeta": { "httpStatus": 200, "responseCode": "0000" }
  },
  "nextActionHint": "Please commit the payment."
}
```

Örnek cevap (3D gerekiyor). `redirectUrl`'in sonundaki `returnUrl`'i SDK ekler:

```json
{
  "success": true,
  "status": "ACTION_REQUIRED",
  "source": "MASTERPASS",
  "message": "3D Secure verification is required. You will be redirected to the 3D Secure screen.",
  "actionType": "3D",
  "data": {
    "sessionId": "8f6d1c2e-...",
    "status": "ACTION_REQUIRED",
    "actionType": "3D",
    "retrievalReferenceNumber": "200003642398",
    "redirectUrl": "https://acs.bank.com/3d?token=...&returnUrl=https://payment-agent.paywall.com/masterpass/vposcallback?uniqueCode=8dd69b76-...",
    "successUrl": "https://merchant.com/success",
    "failUrl": "https://merchant.com/fail",
    "paymentId": 123456,
    "uniqueCode": "8dd69b76-...",
    "merchantUniqueCode": "MERCHANT-ORDER-123",
    "providerMeta": { "httpStatus": 202, "responseCode": "5010" }
  }
}
```

Örnek cevap (Masterpass ödemeyi reddetti). `data` olmadığı için `providerMeta` cevabın kendisindedir:

```json
{
  "success": false,
  "status": "FAILED",
  "source": "MASTERPASS",
  "message": "Payment failed. Masterpass response code: 5195. Please check your information and try again.",
  "errorCode": "5195",
  "providerMeta": { "httpStatus": 400, "responseCode": "5195" }
}
```

| Sonuç | Anlamı | Ne yapılmalı |
|---|---|---|
| `SUCCESS` | Ödeme tamamlandı (Masterpass `0000`) | Başarı ekranı. `nextActionHint` "Please commit the payment.": siparişi backend'de tamamla |
| `ACTION_REQUIRED` · `BANK_OTP` / `MASTERPASS_OTP` | OTP gerekiyor | [OTP akışı](#5-otp-akışı); `verifyOtp` `SUCCESS` dönerse ödeme tamamdır |
| `ACTION_REQUIRED` · `THREE_D` | 3D gerekiyor | `data.redirectUrl`'i aç ([3D akışı](#6-3d-secure-akışı)) |
| `FAILED` · `source = SDK` | Parametre hatası; istek gönderilmedi | `field` / `message`'a göre düzelt |
| `FAILED` · `source = PAYWALL` | Paywall ödeme kaydını açmadı | `errorCode` / `message`'ı göster |
| `FAILED` · `source = MASTERPASS` | Masterpass ödemeyi reddetti | `errorCode` / `message`'ı göster. SDK ödemeyi Paywall'a başarısız olarak bildirdi; kullanıcı tekrar deneyebilir |
| `FAILED` · `MASTERPASS_TIMEOUT` | Masterpass zamanında cevap vermedi; ödemenin sonucu belirsiz | Tekrar denemeden önce ödemenin durumunu backend'inizden (Paywall'dan) sorgulayın |

`maskedCard`: ilk 8 hane kart alanından, son 4 hane Masterpass'ın döndüğü maskeli numaradan gelir. Masterpass'ın
maskeli numara dönmediği durumlarda (ör. 3D başlangıcı) yeni kartla ödemede `null` olabilir. Kayıtlı kartla ödemede
verilen `cardBin` ve `maskedCard`'dan oluşur; Masterpass kartı yalnızca son 2 hanesi açık listelediyse
(`55711355******75`) `last4` `**75` olur.

### 4.10 `payment.initWithRegisteredCard`

Kayıtlı kartla ödemenin kısa yolu. Sonuçları `payment.init` ile aynıdır.

```kotlin
val response = PaywallSdk.payment.initWithRegisteredCard(
    RegisteredCardPaymentParams(
        alias = card.cardAlias!!,
        maskedCard = card.maskedCardNumber!!,
        cardBin = card.cardBin!!,
        cardHolderName = "Ad Soyad",
        paymentDetail = paymentDetail,
        products = products,
    ),
)
```

### 4.11 `payment.registerAndPurchase`

Kartı kullanıcının Masterpass hesabına kaydederek öder (tek adımda). Sonuçları `payment.init` ile aynıdır.

```kotlin
val response = PaywallSdk.payment.registerAndPurchase(
    RegisterAndPurchaseParams(
        accountKey = "905551234567",
        merchantUserId = "MERCHANT_USER_ID",
        paymentDetail = paymentDetail,
        cardData = RegisterCardData(
            cardNumber = cardNumberField.input,
            cardHolderName = "Ad Soyad",
            expiryDate = "1228",              // MMYY
            cvv = cvvField.input,
            cardAlias = "Maaş kartım",
        ),
        termsConsent = termsCheckBox.input,   // kart kaydedildiği için zorunlu
        products = products,
    ),
)
```

Farkı: koşul kutusu işaretli değilse `FAILED` · `TERMS_NOT_ACCEPTED`.

---

## 5. OTP akışı

Bir fonksiyon `ACTION_REQUIRED` ile `BANK_OTP` ya da `MASTERPASS_OTP` dönerse kullanıcının telefonuna SMS kodu
gitmiştir.

1. `PaywallOtpField` içeren bir ekran göster.
2. Kullanıcı kodu girince `verifyOtp(VerifyOtpParams(otpField.input))` çağır.
3. Kod gelmezse `resendOtp()` çağır.
4. `verifyOtp` sonucuna göre:
   - `SUCCESS` → OTP bir ödemeye aitse ödeme tamamdır; hesap işlemine aitse (ör. `linkMerchant`) o işlemi tekrar
     çağır.
   - `ACTION_REQUIRED` (OTP) → ikinci bir kod gerekiyor; ekranı tekrar göster.
   - `ACTION_REQUIRED` (`THREE_D`) → `data.redirectUrl`'i aç.
   - `FAILED` · `OTP_IS_NOT_VALID` → yanlış kod; kullanıcı tekrar denesin.

OTP akışının durumunu SDK tutar; `verifyOtp`'a token vermezsiniz.

## 6. 3D Secure akışı

Bir fonksiyon `ACTION_REQUIRED` ile `THREE_D` dönerse:

1. `data.redirectUrl`'i bir **WebView**'da aç. Adresin sonuna Paywall dönüş adresi (`returnUrl`) SDK tarafından
   eklenmiştir; değiştirme.
2. Kullanıcı bankanın sayfasında doğrular; sonuç Paywall'a gider.
3. Sayfa merchant'ın `successUrl` veya `failUrl`'ine yönlendiğinde WebView'ı kapat.
4. Ödemenin kesin sonucunu kendi backend'inizden (Paywall'dan) öğrenin.

```kotlin
webView.settings.javaScriptEnabled = true
webView.webViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (url.startsWith(successUrl) || url.startsWith(failUrl)) closeThreeD()
    }
}
webView.loadUrl(redirectUrl)
```

---

## 7. Hata kodları

`errorCode` yalnızca `FAILED`'da dolar. Değerler web SDK ile aynıdır; sabitler `SdkErrorCodes` içindedir
(ör. `SdkErrorCodes.OTP_IS_NOT_VALID`).

**Çağrı sırası ve session:** `SDK_NOT_INITIALIZED`, `SESSION_NOT_STARTED`, `MISSING_TOKEN`,
`PROVIDER_NOT_INITIALIZED`, `SESSION_EXPIRED` (yeni token ile `initPaywallSdk`), `SDK_NOT_LOADED`,
`INVALID_SESSION`, `MISSING_SESSION_ID`.

**Başlatma:** `VERIFY_RESPONSE_INVALID`, `MASTERPASS_OBJECT_NULL`, `INIT_WITH_MASTERPASS_TOKEN_ERROR`,
`MISSING_MERCHANT_ID`, `MASTERPASS_PROVIDER_INIT_ERROR`.

**Parametre kontrolü** (`field` hatalı alanı gösterir): `MISSING_USER_ID`, `MISSING_ACCOUNT_KEY`,
`MISSING_CARD_ALIAS`, `MISSING_CARD_HOLDER_NAME`, `MISSING_CARD_NUMBER`, `INVALID_CARD_NUMBER_FORMAT`,
`MISSING_EXPIRY_DATE`, `INVALID_EXPIRY_DATE_FORMAT`, `MISSING_CVV`, `INVALID_CVV_FORMAT`,
`MISSING_REQUEST_REFERENCE_NUMBER`, `MISSING_OTP_CODE`, `INVALID_AMOUNT`, `INVALID_CURRENCY_ID`,
`MISSING_MERCHANT_UNIQUE_CODE`, `MISSING_TRACKING_CODE`, `MISSING_SUCCESS_URL`, `MISSING_FAIL_URL`,
`MISSING_CLIENT_IP`, `ALIAS_REQUIRED`, `MISSING_OWNER_NAME`, `MISSING_CARD_DATA`, `TERMS_NOT_ACCEPTED` (mobil SDK'lara
özel; web SDK'da yok).

**İşlem:** `USER_NOT_FOUND`, `TOKEN_ALREADY_USED`, `DELETE_FAILED`, `UNKNOWN_ERROR`.

**Ödeme:** `PAYWALL_INIT_FAILED` (ya da Paywall'ın kendi `ErrorCode`'u; Paywall'a ulaşılamadığında da bu döner),
`MISSING_MASTERPASS_REQUEST_BODY`, `PAYMENT_INIT_FAILED`, `MASTERPASS_ERROR` (Masterpass'a ulaşılamadı),
`MASTERPASS_TIMEOUT` (Masterpass zamanında cevap vermedi; ödemenin sonucu belirsizdir, tekrar denemeden önce
sorgulayın), `SDK_ERROR` (beklenmeyen hata).

**Masterpass'ın bilinen hataları** (SDK kodu + açıklayıcı mesaj):

| Kod | Masterpass kodu | Anlamı |
|---|---|---|
| `4001` | `OTP_IS_NOT_VALID` | OTP yanlış |
| `4002` | `ACCOUNT_NOT_LINKED_TO_MERCHANT` | Hesap merchant'a bağlı değil |
| `4003` | `USER_NOT_FOUND` | Kullanıcı bulunamadı |
| `4004` | `CARD_ALREADY_EXISTS` | Kart zaten kayıtlı |
| `4005` | `MERCHANT_USER_ID_ALREADY_IN_USE` | Kullanıcı ID'si kullanımda |
| `4006` | `FRAUD_DETECTED_DUE_TO_REPETITIVE_DELETION` | Art arda silme nedeniyle reddedildi |
| `4007` | `INVALID_ACCOUNT_KEY_IN_TOKEN` | Telefon numarası token'dakiyle uyuşmuyor |
| `4008` | `INVALID_CARD_NUMBER` | Kart numarası geçersiz |
| `4009` | `INVALID_EXPIRY_DATE` | Son kullanma tarihi geçersiz |
| `4010` | `INVALID_CVV` | CVV geçersiz |
| `4011` | `MISSING_CARD_ALIAS` | Kart adı eksik |
| `4012` | `MISSING_CARD_HOLDER_NAME` | Kart sahibi eksik |

**Diğer Masterpass hataları** (ör. `ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS`, `TOKEN_HAS_EXPIRED`) `errorCode`'da
Masterpass'ın kendi koduyla, `message`'da Masterpass'ın mesajıyla döner.

---

## 8. Uçtan uca örnek

```kotlin
class CheckoutViewModel : ViewModel() {

    fun start(context: Context, token: String) = viewModelScope.launch {
        val init = PaywallSdk.initPaywallSdk(context, PaywallEnvironment.TEST, token, includeMasterpassSession = true)
        if (init.status != SdkStatus.SUCCESS) return@launch showError(init)

        val provider = PaywallSdk.providers.masterpass.init()
        if (provider.status != SdkStatus.SUCCESS) return@launch showError(provider)

        val session = init.data?.body?.masterpass
        val cards = PaywallSdk.providers.masterpass.getCardList(
            GetCardListParams(accountKey = session?.userPhone.orEmpty(), userId = session?.userId.orEmpty()),
        )
        when {
            cards.status == SdkStatus.SUCCESS -> showCards(cards.data?.cards.orEmpty())
            cards.actionType == SdkActionType.MERCHANT_LINK_REQUIRED -> askToLinkAccount()
            cards.actionType == SdkActionType.MASTERPASS_OTP -> showOtpScreen()
            else -> showNewCardForm()   // ör. ACCOUNT_NOT_FOUND: yeni kartla ödeme
        }
    }

    fun payWithNewCard(card: CardNumberInput, cvv: CvvInput, holder: String, expiryMMYY: String) =
        viewModelScope.launch {
            val response = PaywallSdk.payment.init(
                PaymentInitParams(
                    paymentSource = PaymentSource.MANUAL_CARD,
                    paymentDetail = paymentDetail(),
                    cardData = PaymentCardData(
                        cardNumber = card,
                        cvv = cvv,
                        cardHolderName = holder,
                        expiryDate = expiryMMYY,
                    ),
                    products = products(),
                ),
            )
            when (response.status) {
                SdkStatus.SUCCESS -> showSuccess(response.data?.paymentId)
                SdkStatus.ACTION_REQUIRED -> when (response.actionType) {
                    SdkActionType.THREE_D -> openThreeD(response.data?.redirectUrl)
                    SdkActionType.BANK_OTP, SdkActionType.MASTERPASS_OTP -> showOtpScreen()
                    else -> Unit
                }
                SdkStatus.FAILED -> showError(response)
            }
        }

    fun submitOtp(otp: OtpInput) = viewModelScope.launch {
        val response = PaywallSdk.providers.masterpass.verifyOtp(VerifyOtpParams(otp))
        when {
            response.status == SdkStatus.SUCCESS -> onOtpVerified()
            response.actionType == SdkActionType.THREE_D -> openThreeD(response.data?.redirectUrl)
            response.status == SdkStatus.ACTION_REQUIRED -> showOtpScreen()   // ikinci OTP
            else -> showError(response)                                        // ör. OTP_IS_NOT_VALID
        }
    }
}
```

---

## 9. Güvenlik

- Kart numarası, CVV ve OTP uygulamanıza ve Paywall'a hiç girmez; Masterpass güvenli alanında kalır ve Masterpass
  şifreleyerek gönderir.
- Güvenli alanlar değeri erişilebilirlik servislerine, otomatik doldurma servislerine, klavye öğrenmesine ve
  kopyala / yapıştıra kapatır; üstü başka bir pencereyle örtülüyken dokunuş kabul etmez.
- **Ödeme ekranlarında `FLAG_SECURE` kullanın** (ekran görüntüsü, ekran kaydı ve "son uygulamalar" önizlemesine
  karşı):

  ```kotlin
  window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
  ```
- Token ve session yalnızca bellekte tutulur.
- `PaywallSdk.setNetworkListener(context, listener)` SDK'nın HTTP trafiğini hata ayıklama için dinler ve yalnızca
  debug build'de çalışır; release'de kurulmaz (`false` döner).
