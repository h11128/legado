package io.legado.app.lib.cronet

import android.os.ConditionVariable
import androidx.annotation.Keep
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.UrlRequest
import java.io.IOException

@Keep
class OldCallback(originalRequest: Request, mCall: Call, readTimeoutMillis: Int) :
    AbsCallBack(originalRequest, mCall, readTimeoutMillis) {

    private val mResponseCondition = ConditionVariable()
    private var mException: IOException? = null

    @Throws(IOException::class)
    override fun waitForDone(urlRequest: UrlRequest): Response {
        val timeOutMs: Long = (mCall.timeout().timeoutNanos() / 1_000_000L)
            .let { if (it > 0) it else 60_000L }
            .coerceAtMost(90_000L)
        urlRequest.start()
        startCheckCancelJob(urlRequest)
        mResponseCondition.block(timeOutMs)
        if (!urlRequest.isDone) {
            urlRequest.cancel()
            mException = IOException("Cronet timeout after wait ${timeOutMs}ms")
        }

        if (mException != null) {
            throw mException as IOException
        }
        return mResponse
    }

    override fun onError(error: IOException) {
        mException = error
        mResponseCondition.open()
    }

    override fun onSuccess(response: Response) {
        mResponseCondition.open()
    }
}
