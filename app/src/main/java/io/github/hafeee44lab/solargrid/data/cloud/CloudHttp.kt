package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import io.github.hafeee44lab.solargrid.data.SecureBox
import io.github.hafeee44lab.solargrid.data.StateStore
import io.github.hafeee44lab.solargrid.data.api.SolarMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/** An HTTP answer, whatever its status. Header names are lower case. */
data class HttpReply(
    val code: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap()
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    fun headerValues(name: String): List<String> = headers[name.lowercase()].orEmpty()
}

/** The brand clouds speak plain HTTP with their own signing schemes; this is all they need. */
interface CloudHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpReply
    suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): HttpReply
}

object OkCloudHttp : CloudHttp {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // A redirect is how some of these clouds say "signed out"; the source needs to see it.
            .followRedirects(false)
            .build()
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpReply =
        send(Request.Builder().url(url).get(), headers)

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): HttpReply =
        send(Request.Builder().url(url).post(RequestBody.create(MediaType.parse(contentType), body)), headers)

    private suspend fun send(builder: Request.Builder, headers: Map<String, String>): HttpReply = withContext(Dispatchers.IO) {
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            HttpReply(
                code = response.code(),
                body = response.body()?.string().orEmpty(),
                headers = response.headers().toMultimap().mapKeys { it.key.lowercase() }
            )
        }
    }
}

/** Where a brand source keeps its sign-in session between polls, and counts what it spends. */
interface SessionCache {
    /** An opaque session (token, cookie) and the time it stops being valid. */
    var session: Pair<String, Long>?
    fun countCall()
}

/**
 * Brand sessions live in the same slot as the Solarman token, sealed, and tagged with the brand
 * so switching brands can never hand one cloud another's token.
 */
class StateSessionCache(private val state: StateStore, private val brand: CloudBrand) : SessionCache {
    override var session: Pair<String, Long>?
        get() {
            val (stored, expiry) = state.token ?: return null
            val prefix = "${brand.name}:"
            if (!stored.startsWith(prefix)) return null
            return SecureBox.open(stored.removePrefix(prefix))?.let { it to expiry }
        }
        set(value) {
            state.token = value?.let { (plain, expiry) -> "${brand.name}:${SecureBox.seal(plain)}" to expiry }
        }

    override fun countCall() = state.countApiCall()
}

/**
 * Keeps a brand's cloud from being asked again sooner than it allows. A manual refresh, a second
 * screen or a restarted service inside the gap gets the last answer back instead of a new call.
 */
class CallSpacing(private val minGapMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var lastAt = 0L
    private var last: FetchResult? = null

    suspend fun fetch(block: suspend () -> FetchResult): FetchResult = lock.withLock {
        val now = clock()
        val previous = last
        if (previous != null && now - lastAt in 0 until minGapMs) return previous
        return block().also {
            last = it
            lastAt = now
        }
    }
}

class SpacedSource(private val inner: ReadingSource, private val spacing: CallSpacing) : ReadingSource {
    override suspend fun fetch(): FetchResult = spacing.fetch { inner.fetch() }
}

/** Builds Roshni's metric list from a brand's numbers, skipping whatever the brand didn't send. */
internal class MetricsBuilder {
    private val list = mutableListOf<SolarMetric>()

    fun add(key: String, value: Double?, unit: String, name: String): MetricsBuilder {
        if (value != null && value.isFinite()) list += SolarMetric(key, name, trim(value), unit)
        return this
    }

    fun text(key: String, value: String?, name: String): MetricsBuilder {
        if (!value.isNullOrBlank()) list += SolarMetric(key, name, value, null)
        return this
    }

    fun build(): List<SolarMetric> = list.toList()

    private fun trim(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
}

/** Lenient number reading: the clouds send numbers as strings, with units, or as "--". */
internal fun parseNumber(raw: Any?): Double? = when (raw) {
    null -> null
    is Number -> raw.toDouble().takeIf { it.isFinite() }
    else -> raw.toString().trim().replace(",", "").let { s ->
        s.toDoubleOrNull() ?: Regex("^-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull()
    }?.takeIf { it.isFinite() }
}
