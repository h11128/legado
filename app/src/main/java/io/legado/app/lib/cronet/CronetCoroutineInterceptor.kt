package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.receiveHeaders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Keep
@Suppress("unused")
class CronetCoroutineInterceptor(private val cookieJar: CookieJar) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val original: Request = chain.request()
        //Cronet未初始化
        return if (!CronetLoader.install() || cronetEngine == null) {
            chain.proceed(original)
        } else try {
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")
            if (cookieJar != CookieJar.NO_COOKIES) {
                val cookieStr = getCookie(original.url)
                //设置Cookie
                if (cookieStr.length > 3) {
                    builder.addHeader("Cookie", cookieStr)
                }
            }

            val newReq = builder.build()
            val call = chain.call()
            // Never run Cronet unbounded: callTimeout==0 previously skipped withTimeout and
            // could outlive outer 换源 withTimeout (agent saw >80s stalls). Cap at 90s.
            val timeoutMs = (call.timeout().timeoutNanos() / 1_000_000L)
                .let { if (it > 0) it else 60_000L }
                .coerceAtMost(90_000L)
                .coerceAtLeast(1L)
            runBlocking {
                withTimeout(timeoutMs) {
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    }
                    proceedWithCronet(newReq, call, chain.readTimeoutMillis()).also { response ->
                        cookieJar.receiveHeaders(newReq.url, response.headers)
                    }
                }
            }

        } catch (e: Exception) {
            // Timeout / cancel must NOT fall back to OkHttp (would stack another ~60s).
            if (CronetHardStop.isHardStop(e) || chain.call().isCanceled()) {
                throw CronetHardStop.asIOException(e)
            }
            //不能抛出错误,抛出错误会导致应用崩溃
            //遇到Cronet处理有问题时的情况，如证书过期等等，回退到okhttp处理
            if (!e.message.toString().contains("ERR_CERT_", true)
                && !e.message.toString().contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            chain.proceed(original)
        }

    }


    private suspend fun proceedWithCronet(
        request: Request,
        call: Call,
        readTimeoutMillis: Int
    ): Response =
        suspendCancellableCoroutine<Response> { coroutine ->

            val callBack = object : AbsCallBack(request, call, readTimeoutMillis) {
                override fun waitForDone(urlRequest: UrlRequest): Response {
                    TODO("Not yet implemented")
                }

                override fun onError(error: IOException) {
                    coroutine.resumeWithException(error)
                }

                override fun onSuccess(response: Response) {
                    coroutine.resume(response)
                }

                override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                    super.onCanceled(request, info)
                    coroutine.cancel()
                }


            }

            val req = buildRequest(request, callBack)?.also {
                callBack.startCheckCancelJob(it)
                it.start()
            }
            coroutine.invokeOnCancellation {
                req?.cancel()
            }


        }


    /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
    private fun getCookie(url: HttpUrl): String = buildString {
        val cookies = cookieJar.loadForRequest(url)
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }
}