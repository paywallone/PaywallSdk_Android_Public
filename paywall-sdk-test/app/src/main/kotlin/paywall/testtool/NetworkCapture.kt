package paywall.testtool

import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.mutableStateListOf
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import paywall.sdk.PaywallNetworkEvent
import paywall.sdk.PaywallNetworkListener
import paywall.sdk.PaywallNetworkSource

/**
 * The "Ağ" tab's data: SDK network events plus the Masterpass request bodies and headers, which Masterpass writes
 * only to Logcat (`Request Body:` / `Request Headers:`). The tool reads its own Logcat for them; this is a test
 * tool technique, not something the SDK does.
 */
class NetworkCapture : PaywallNetworkListener {

    class LogcatLine(val timeMillis: Long, val text: String)

    val events = mutableStateListOf<PaywallNetworkEvent>()
    private val masterpassBodies = mutableStateListOf<LogcatLine>()
    private val masterpassHeaders = mutableStateListOf<LogcatLine>()

    private val main = Handler(Looper.getMainLooper())
    private var logcat: java.lang.Process? = null

    override fun onNetworkEvent(event: PaywallNetworkEvent) {
        main.post { events.add(0, event) }
    }

    fun clear() {
        events.clear()
        masterpassBodies.clear()
        masterpassHeaders.clear()
    }

    /** Masterpass request body logged while [event] was running. */
    fun masterpassRequestBody(event: PaywallNetworkEvent): String? =
        masterpassBodies.lastOrNull { it.timeMillis in window(event) }?.text

    fun masterpassRequestHeaders(event: PaywallNetworkEvent): List<String> =
        masterpassHeaders.filter { it.timeMillis in window(event) }.map { maskSecretHeader(it.text) }

    private fun window(event: PaywallNetworkEvent): LongRange =
        (event.startedAtMillis - MARGIN_MS)..(event.startedAtMillis + event.durationMs + MARGIN_MS)

    /** Streams this process' Logcat (no permission needed for an app's own logs). */
    fun startLogcat() {
        if (logcat != null) return
        Thread({
            runCatching {
                val process = ProcessBuilder(
                    "logcat", "-v", "epoch", "-T", "1", "--pid=${Process.myPid()}",
                ).redirectErrorStream(true).start()
                logcat = process
                process.inputStream.bufferedReader().forEachLine(::onLogcatLine)
            }
        }, "masterpass-logcat").apply { isDaemon = true }.start()
    }

    fun stopLogcat() {
        logcat?.destroy()
        logcat = null
    }

    private fun onLogcatLine(line: String) {
        val time = EPOCH.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return
        when {
            line.contains(BODY_TAG) -> {
                val body = line.substringAfter(BODY_TAG).removePrefix("Request Body:").trim()
                if (body.isNotEmpty() && body != "null") main.post { masterpassBodies += LogcatLine(time, body) }
            }
            line.contains(HEADERS_TAG) -> {
                val header = line.substringAfter(HEADERS_TAG).removePrefix("Request Headers:").trim()
                if (header.isNotEmpty()) main.post { masterpassHeaders += LogcatLine(time, header) }
            }
        }
    }

    companion object {
        private const val BODY_TAG = "Request Body:: "
        private const val HEADERS_TAG = "Request Headers:: "
        private const val MARGIN_MS = 150L
        private val EPOCH = Regex("^\\s*(\\d+\\.\\d+)\\s")

        private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        /** JSON with indentation (as in browser dev tools); other text unchanged. */
        fun prettyJson(text: String?): String {
            if (text.isNullOrBlank()) return "(boş)"
            return runCatching { pretty.toJson(JsonParser.parseString(text)) }.getOrDefault(text)
        }

        private fun maskSecretHeader(header: String): String {
            val name = header.substringBefore(':')
            if (!name.equals("Authorization", true)) return header
            val value = header.substringAfter(':').trim()
            // OkHttp's Headers.toString() redacts Authorization as "██"; Masterpass logs headers that way.
            if (value.all { it == '█' }) return "$name: ██ (OkHttp loglarda gizler; gerçek istekte token var)"
            return if (value.length <= 12) "$name: ***" else "$name: ${value.take(6)}…${value.takeLast(4)}"
        }

        fun sourceName(source: PaywallNetworkSource): String = when (source) {
            PaywallNetworkSource.PAYWALL -> "Paywall"
            PaywallNetworkSource.MASTERPASS -> "Masterpass"
        }
    }
}
