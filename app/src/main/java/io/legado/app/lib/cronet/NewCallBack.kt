package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.UrlRequest
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SuppressLint("ObsoleteSdkInt")
@Keep
@RequiresApi(api = Build.VERSION_CODES.N)
class NewCallBack(originalRequest: Request, mCall: Call, readTimeoutMillis: Int) :
    AbsCallBack(originalRequest, mCall, readTimeoutMillis) {

    private val responseFuture = CompletableFuture<Response>()

    @Throws(IOException::class)
    override fun waitForDone(urlRequest: UrlRequest): Response {
        urlRequest.start()
        startCheckCancelJob(urlRequest)
        // Never block forever: callTimeout==0 previously used get() unbounded and could
        // outlive outer 换源 withTimeout (device saw >80s stalls).
        val timeoutNs = mCall.timeout().timeoutNanos().let { if (it > 0) it else 60_000_000_000L }
            .coerceAtMost(90_000_000_000L)
        return try {
            responseFuture.get(timeoutNs, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            urlRequest.cancel()
            throw IOException("Cronet timeout after wait ${timeoutNs / 1_000_000}ms", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException(cause?.message ?: "Cronet failed", cause)
        } catch (e: InterruptedException) {
            urlRequest.cancel()
            Thread.currentThread().interrupt()
            throw IOException("Cronet interrupted", e)
        }
    }

    override fun onError(error: IOException) {
        responseFuture.completeExceptionally(error)
    }

    override fun onSuccess(response: Response) {
        responseFuture.complete(response)
    }
}
