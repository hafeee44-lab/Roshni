package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.domain.GridAnalyzer
import io.github.hafeee44lab.solargrid.domain.Verdict

/** A recorded or synthetic reply from src/test/resources/cloud/. */
fun fixture(path: String): String =
    requireNotNull(CloudFakes::class.java.getResource("/cloud/$path")) { "missing fixture $path" }.readText()

data class SentRequest(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

/** Answers each request with the first route whose key appears in the URL. */
class FakeHttp(private val routes: MutableMap<String, ArrayDeque<HttpReply>> = linkedMapOf()) : CloudHttp {
    val sent = mutableListOf<SentRequest>()

    fun on(urlPart: String, vararg replies: HttpReply): FakeHttp {
        routes.getOrPut(urlPart) { ArrayDeque() }.addAll(replies)
        return this
    }

    private fun answer(url: String): HttpReply {
        val queue = routes.entries.firstOrNull { url.contains(it.key) }?.value ?: error("no route for $url")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpReply {
        sent += SentRequest("GET", url, null, headers)
        return answer(url)
    }

    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): HttpReply {
        sent += SentRequest("POST", url, body, headers)
        return answer(url)
    }
}

class MemorySession(var clockMs: () -> Long = { 0L }) : SessionCache {
    override var session: Pair<String, Long>? = null
    var calls = 0
    override fun countCall() {
        calls++
    }
}

fun ok(body: String, vararg headers: Pair<String, String>) =
    HttpReply(200, body, headers.groupBy({ it.first.lowercase() }, { it.second }))

object CloudFakes {
    /** Runs a reading through the same analysis the monitor uses. */
    fun verdict(result: FetchResult): Verdict? =
        GridAnalyzer.evaluate(GridAnalyzer.read((result as FetchResult.Ok).metrics))
}
