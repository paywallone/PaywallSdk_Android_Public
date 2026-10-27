package paywall.testtool

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import paywall.sdk.PaywallEnvironment
import paywall.sdk.ProviderMeta
import paywall.sdk.SdkActionType
import paywall.sdk.SdkResponse
import paywall.sdk.SdkSource
import paywall.sdk.SdkStatus

/**
 * SDK responses as JSON, as the web SDK prints them: enum values use their wire value (`"3D"`, `"SUCCESS"`) and
 * empty (`null`) fields are left out.
 */
object SdkJson {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .registerTypeAdapter(SdkStatus::class.java, JsonSerializer<SdkStatus> { v, _, _ -> JsonPrimitive(v.wireValue) })
        .registerTypeAdapter(ProviderMeta::class.java, JsonSerializer<ProviderMeta> { v, _, _ -> providerMeta(v) })
        .registerTypeAdapter(SdkSource::class.java, JsonSerializer<SdkSource> { v, _, _ -> JsonPrimitive(v.wireValue) })
        .registerTypeAdapter(SdkActionType::class.java, JsonSerializer<SdkActionType> { v, _, _ -> JsonPrimitive(v.wireValue) })
        .registerTypeAdapter(
            PaywallEnvironment::class.java,
            JsonSerializer<PaywallEnvironment> { v, _, _ -> JsonPrimitive(v.wireValue) },
        )
        .create()

    fun response(response: SdkResponse<*>): JsonObject = JsonObject().apply {
        addProperty("success", response.success)
        add("status", JsonPrimitive(response.status.wireValue))
        add("source", JsonPrimitive(response.source.wireValue))
        addProperty("message", response.message)
        addProperty("errorCode", response.errorCode)
        addProperty("actionType", response.actionType?.wireValue)
        add("data", response.data?.let(gson::toJsonTree))
        add("providerMeta", response.providerMeta?.let(::providerMeta))
        addProperty("actionHint", response.actionHint)
        addProperty("field", response.field)
        addProperty("nextActionHint", response.nextActionHint)
    }

    /** `raw` is JSON text in the SDK; shown as JSON, as the web SDK returns it. */
    private fun providerMeta(meta: ProviderMeta): JsonObject = JsonObject().apply {
        addProperty("httpStatus", meta.httpStatus)
        addProperty("responseCode", meta.responseCode)
        add("raw", meta.raw?.let { raw -> runCatching { JsonParser.parseString(raw) }.getOrElse { JsonPrimitive(raw) } })
    }

    /** Short summary of a response: success, status, action type and message. */
    fun normalized(response: SdkResponse<*>): JsonObject = JsonObject().apply {
        addProperty("success", response.success || response.status == SdkStatus.SUCCESS)
        addProperty("status", response.status.wireValue)
        addProperty("actionType", response.actionType?.wireValue)
        addProperty("message", response.message)
    }

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) -> add(key, gson.toJsonTree(value)) }
    }

    fun pretty(element: JsonElement?): String = if (element == null) "null" else gson.toJson(element)

    fun pretty(text: String?): String =
        runCatching { gson.toJson(JsonParser.parseString(text)) }.getOrDefault(text ?: "null")

    /** Keeps the first 6 and last 4 characters (the merchant's access token in request logs). */
    fun maskToken(token: String?): String? =
        token?.let { if (it.length < 8) "***" else it.take(6) + "..." + it.takeLast(4) }
}
