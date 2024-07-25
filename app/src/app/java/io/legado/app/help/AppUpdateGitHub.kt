package io.legado.app.help

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.AppReleaseInfo
import io.legado.app.model.GithubRelease
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private suspend fun getRecentReleases(): List<AppReleaseInfo> {
        val lastReleaseUrl =
            "https://api.github.com/repos/gedoor/legado/releases?page=1?per_page=1"
        val body = okHttpClient.newCallStrResponse {
            url(lastReleaseUrl)
        }.body
        if (body.isNullOrBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return Gson().fromJsonArray<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val recentReleases = getRecentReleases()
            recentReleases
                .filter { it.appVariant == AppConst.appInfo.appVariant }
                .firstOrNull { it.versionName > AppConst.appInfo.versionName }
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                } ?: throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }


}