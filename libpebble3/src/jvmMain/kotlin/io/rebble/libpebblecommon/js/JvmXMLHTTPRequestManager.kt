package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JvmXMLHTTPRequestManager(
    private val scope: CoroutineScope,
    private val jsThread: ExecutorCoroutineDispatcher,
    private val evalFn: (String) -> Unit,
    appInfo: PbwAppInfo,
) {
    private val logger = Logger.withTag("JvmXHR-${appInfo.longName}")
    private val idCounter = AtomicInteger(0)

    private data class RequestState(
        val method: String = "GET",
        val url: String = "",
        val headers: MutableMap<String, String> = mutableMapOf(),
    )

    private val pending = ConcurrentHashMap<Int, RequestState>()
    private val jobs = ConcurrentHashMap<Int, Job>()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun getXHRInstanceID(): Int {
        val id = idCounter.incrementAndGet()
        pending[id] = RequestState()
        return id
    }

    fun open(instanceId: Int, method: String, url: String, async: Boolean, user: String, password: String) {
        val state = pending[instanceId] ?: return
        val headers = state.headers.toMutableMap()
        if (user.isNotEmpty()) {
            val creds = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
            headers["Authorization"] = "Basic $creds"
        }
        pending[instanceId] = state.copy(method = method, url = url, headers = headers)
    }

    fun setRequestHeader(instanceId: Int, header: String, value: String) {
        pending[instanceId]?.headers?.set(header, value)
    }

    fun send(instanceId: Int, responseType: String, body: String?) {
        val state = pending.remove(instanceId) ?: return
        val job = scope.launch(Dispatchers.IO) {
            try {
                val bodyPublisher = if (body != null && body.isNotEmpty())
                    HttpRequest.BodyPublishers.ofString(body)
                else
                    HttpRequest.BodyPublishers.noBody()

                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(state.url))
                    .method(state.method, bodyPublisher)
                state.headers.forEach { (k, v) -> builder.header(k, v) }

                val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

                val responseBody = when (responseType) {
                    "arraybuffer" -> Base64.getEncoder().encodeToString(response.body())
                    else -> String(response.body(), Charsets.UTF_8)
                }
                val headersJson = buildString {
                    append("{")
                    response.headers().map().entries.joinTo(this, ",") { (k, v) ->
                        val escaped = v.joinToString(", ").replace("\\", "\\\\").replace("\"", "\\\"")
                        "\"${k.lowercase()}\":\"$escaped\""
                    }
                    append("}")
                }
                val escapedBody = responseBody.replace("\\", "\\\\").replace("'", "\\'")
                val status = response.statusCode()

                withContext(jsThread) {
                    evalFn("""(function(){
                        var xhr=XMLHttpRequest._instances.get($instanceId);
                        if(!xhr)return;
                        xhr.readyState=4;
                        xhr._onResponseComplete($headersJson,$status,'$status','$escapedBody');
                        xhr._dispatchEvent('readystatechange',{});
                        xhr._dispatchEvent('load',{});
                        xhr._dispatchEvent('loadend',{});
                    })();""")
                }
            } catch (e: Exception) {
                logger.e(e) { "XHR failed for $instanceId: ${state.url}" }
                withContext(jsThread) {
                    evalFn("""(function(){
                        var xhr=XMLHttpRequest._instances.get($instanceId);
                        if(!xhr)return;
                        xhr._dispatchEvent('error',{type:'error'});
                        xhr._dispatchEvent('loadend',{});
                    })();""")
                }
            } finally {
                jobs.remove(instanceId)
            }
        }
        jobs[instanceId] = job
    }

    fun abort(instanceId: Int) {
        jobs.remove(instanceId)?.cancel()
        pending.remove(instanceId)
        scope.launch(jsThread) {
            evalFn("""(function(){
                var xhr=XMLHttpRequest._instances.get($instanceId);
                if(xhr){xhr._dispatchEvent('abort',{type:'abort'});xhr._dispatchEvent('loadend',{});}
            })();""")
        }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pending.clear()
    }
}
