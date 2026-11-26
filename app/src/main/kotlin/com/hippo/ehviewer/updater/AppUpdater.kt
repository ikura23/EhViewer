package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import tachiyomi.data.release.ArtifactsResponse
import tachiyomi.data.release.CommitComparison
import tachiyomi.data.release.Release
import tachiyomi.data.release.Repository
import tachiyomi.data.release.WorkflowRuns
import tachiyomi.data.release.downloadLinkOrNull

const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
const val LATEST_RELEASE_URL = "$API_URL/releases/latest"

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): EhRelease? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            if (Settings.useCIUpdateChannel.value) {
                val curSha = BuildConfig.COMMIT_SHA
                val branch = statement(API_URL).executeAndParseAs<Repository>().defaultBranch
                val run = statement("$API_URL/actions/runs?event=push&status=success&branch=$branch")
                    .executeAndParseAs<WorkflowRuns>()
                    .workflowRuns
                    .firstOrNull() ?: return null
                val shortSha = run.headSha.take(7)
                if (shortSha != curSha) {
                    val artifactsResp = statement("$API_URL/actions/runs/${run.id}/artifacts")
                        .executeAndParseAs<ArtifactsResponse>()
                    val archiveUrl = artifactsResp.artifacts.downloadLinkOrNull ?: return null
                    val changelog = runSuspendCatching {
                        val commitComparisonUrl = "$API_URL/compare/$curSha...$shortSha"
                        val result = statement(commitComparisonUrl).executeAndParseAs<CommitComparison>()
                        // TODO: Prettier format, Markdown?
                        result.commits.joinToString("\n") { commit ->
                            "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
                        }
                    }.getOrDefault(run.title)
                    return EhRelease(shortSha, changelog, archiveUrl)
                }
            } else {
                val curVersion = BuildConfig.RAW_VERSION_NAME
                val release = statement(LATEST_RELEASE_URL).executeAndParseAs<Release>()
                val latestVersion = release.version.trim()
                val description = release.info
                val downloadUrl = release.getDownloadLinkOrNull() ?: return null
                if (isRemoteVersionNewer(latestVersion, curVersion)) {
                    return EhRelease(latestVersion.removePrefix("v").removePrefix("V"), description, downloadUrl)
                }
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
        val isZip = url.endsWith("zip")
        timeoutBySpeed(
            url,
            {
                statement(url) {
                    // https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
                    if (!isZip) accept(ContentType.Application.OctetStream)
                    it()
                }
            },
            { _, _, _ -> },
            { response ->
                if (isZip) {
                    response.bodyAsChannel().toInputStream().use { stream ->
                        ZipInputStream(stream).use { zip ->
                            zip.nextEntry
                            path.write { transferFrom(zip.asSource()) }
                        }
                    }
                } else {
                    response.bodyAsChannel().copyTo(path)
                }
            },
        )
    }
}

suspend inline fun statement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    apply(builder)
}

data class EhRelease(
    val version: String,
    val changelog: String,
    val downloadLink: String,
)

/**
 * 比较远端发布版本号是否严格高于本地当前版本：
 * - 自动忽略前导 `v`/`V`；
 * - 逐段比较点分数字版本号；
 * - 若主版本号相等，且本地为 `-SNAPSHOT` 而远端为正式版，视为有新正式版可更新；
 * - 同版本或更低版本返回 `false`，避免稳定渠道反复提示更新。
 */
internal fun isRemoteVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
    val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V")
    val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
    if (cleanRemote.isEmpty() || cleanCurrent.isEmpty()) return false

    val remoteCore = cleanRemote.substringBefore('-')
    val currentCore = cleanCurrent.substringBefore('-')
    val remoteParts = remoteCore.split('.').map { it.toIntOrNull() ?: 0 }
    val currentParts = currentCore.split('.').map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(remoteParts.size, currentParts.size)

    for (i in 0 until maxLen) {
        val r = remoteParts.getOrElse(i) { 0 }
        val c = currentParts.getOrElse(i) { 0 }
        if (r != c) return r > c
    }

    val currentIsSnapshot = cleanCurrent.contains("SNAPSHOT", ignoreCase = true)
    val remoteIsSnapshot = cleanRemote.contains("SNAPSHOT", ignoreCase = true)
    return currentIsSnapshot && !remoteIsSnapshot
}
