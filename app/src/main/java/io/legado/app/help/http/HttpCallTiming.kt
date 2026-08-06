package io.legado.app.help.http

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Measures OkHttp **dispatcher queue** wait (enqueue → first app interceptor).
 *
 * UI「响应时间」should use content wall minus [Acc.queueMs]. Acc is installed via
 * [asContextElement] so suspend / OkHttp thread hops still see it in [tagRequest].
 */
object HttpCallTiming {

    class Acc {
        private val queueNs = LongAdder()
        private val calls = AtomicLong(0)

        fun addQueueNs(ns: Long) {
            if (ns > 0) queueNs.add(ns)
        }

        fun onCall() {
            calls.incrementAndGet()
        }

        val queueMs: Long get() = queueNs.sum() / 1_000_000L
        val callCount: Long get() = calls.get()
    }

    private val current = ThreadLocal<Acc?>()

    /**
     * Bind [Acc] for the duration of [block]. Uses [asContextElement] so every
     * resume thread restores ThreadLocal before [tagRequest] runs.
     */
    suspend fun <T> measure(block: suspend () -> T): Pair<T, Acc> {
        val acc = Acc()
        return withContext(current.asContextElement(acc)) {
            block() to acc
        }
    }

    fun currentAcc(): Acc? = current.get()

    fun tagRequest(builder: Request.Builder): Request.Builder {
        val acc = current.get() ?: return builder
        return builder.tag(Acc::class.java, acc)
    }

    fun markDispatcherReleased(call: Call, request: Request = call.request()) {
        val acc = request.tag(Acc::class.java) ?: return
        val t0 = callStartNs.remove(call) ?: return
        acc.addQueueNs(System.nanoTime() - t0)
    }

    private val callStartNs = ConcurrentHashMap<Call, Long>()

    val eventListenerFactory = EventListener.Factory { call ->
        val acc = call.request().tag(Acc::class.java) ?: return@Factory EventListener.NONE
        object : EventListener() {
            override fun callStart(call: Call) {
                acc.onCall()
                callStartNs[call] = System.nanoTime()
            }

            override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
                markDispatcherReleased(call)
            }

            override fun dnsStart(call: Call, domainName: String) {
                markDispatcherReleased(call)
            }

            override fun connectStart(
                call: Call,
                inetSocketAddress: java.net.InetSocketAddress,
                proxy: java.net.Proxy
            ) {
                markDispatcherReleased(call)
            }

            override fun requestHeadersStart(call: Call) {
                markDispatcherReleased(call)
            }

            override fun callEnd(call: Call) {
                markDispatcherReleased(call)
                callStartNs.remove(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                markDispatcherReleased(call)
                callStartNs.remove(call)
            }
        }
    }

    /**
     * First application interceptor: Cronet may skip network EventListener callbacks,
     * but still runs after the dispatcher permits the call.
     */
    val dispatcherReleaseInterceptor = Interceptor { chain ->
        markDispatcherReleased(chain.call(), chain.request())
        chain.proceed(chain.request())
    }
}
