package me.petertian.onepku.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.petertian.onepku.BuildConfig
import me.petertian.onepku.core.network.HttpFactory
import me.petertian.onepku.core.network.requireBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class UpdateException(message: String) : Exception(message)

/** Releases 里可用于本机升级的安卓包。 */
data class AndroidRelease(
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

/** 「没有更新的版本」与「有新版但这一版没发安卓包」是两回事，不能都报成已是最新。 */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data object NoAndroidPackage : UpdateCheck
    data class Available(val release: AndroidRelease) : UpdateCheck
}

/** 应用内检查更新：读公开的 GitHub Releases，不发送任何账号数据，也不自动检查。 */
@Singleton
class UpdateApi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpFactory: HttpFactory,
) {

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheck =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = try {
                client(readTimeoutSec = 30).newCall(request).execute()
            } catch (e: Exception) {
                throw UpdateException("连不上 GitHub：${e.message}")
            }
            val text = response.use {
                if (it.code == 403 || it.code == 429) throw UpdateException("GitHub 请求次数已达上限，稍后再试")
                if (!it.isSuccessful) throw UpdateException("读取发布信息失败: HTTP ${it.code}")
                it.requireBody().string()
            }
            parseCheck(text, currentVersion)
        }

    /** 下载到缓存目录，校验 sha256、包名与签名后才交出去；任何一项不过都不留半成品。 */
    suspend fun download(
        release: AndroidRelease,
        onProgress: (done: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!isAssetUrl(release.url)) throw UpdateException("下载地址不在本仓库的 Releases 里")
        val dest = File(dir(), "onepku-android-v${release.version}.apk")
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(release.url).build()
        client(readTimeoutSec = 120).newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("下载失败: HTTP ${resp.code}")
            resp.requireBody().byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val total = resp.body?.contentLength()?.takeIf { it > 0 }
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > MAX_APK_BYTES) {
                            dest.delete()
                            throw UpdateException("安装包超出大小上限")
                        }
                        output.write(buffer, 0, read)
                        onProgress(done, total)
                    }
                }
            }
        }
        try {
            verify(release, dest)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        dest
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 系统「允许安装未知应用」的设置页，未授权时把用户带到那里。 */
    fun unknownSourcesIntent(): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriFor(file), APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launchInstall(file: File): Boolean = runCatching {
        context.startActivity(installIntent(file))
    }.isSuccess

    fun openInstallPermissionSettings(): Boolean = runCatching {
        context.startActivity(unknownSourcesIntent())
    }.isSuccess

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)

    private fun dir() = File(context.cacheDir, "updates")

    @Suppress("DEPRECATION")
    private fun verify(release: AndroidRelease, file: File) {
        // GitHub 的资产摘要字段是后加的；没有它就没有可核对的完整性凭据，宁可不装。
        val expected = release.sha256
            ?: throw UpdateException("发布信息里没有校验和，暂不自动安装")
        if (!digestMatches(expected, sha256Of(file))) {
            throw UpdateException("安装包校验和不一致，已删除")
        }
        val info = archiveInfo(file.absolutePath)
            ?: throw UpdateException("读不到安装包信息")
        if (info.packageName != context.packageName) {
            throw UpdateException("这个安装包不是本机应用的更新")
        }
        val apk = signaturesOf(info)
        val installed = signaturesOf(context.packageManager.getPackageInfo(context.packageName, signatureFlags()))
        if (apk.isEmpty() || installed.isEmpty()) throw UpdateException("读不到签名信息，无法确认来源")
        if (!signaturesMatch(apk, installed)) {
            throw UpdateException("这个安装包与本机应用不是同一把钥匙签的，需要先卸载旧版本")
        }
    }

    private fun client(readTimeoutSec: Long) = httpFactory.client(
        followRedirects = true,
        ua = USER_AGENT,
        readTimeoutSec = readTimeoutSec,
    )

    @Suppress("DEPRECATION")
    private fun archiveInfo(path: String) = context.packageManager.getPackageArchiveInfo(
        path,
        signatureFlags(),
    )

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: android.content.pm.PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                (if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory)
                    ?.map { sig -> sig.toByteArray() }
            } ?: info.signatures?.map { it.toByteArray() } ?: emptyList()
        } else {
            info.signatures?.map { it.toByteArray() } ?: emptyList()
        }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Northodieart/onePKU/releases/latest"
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        /** 安卓包名与版本由文件名给出：Release 的标签跟着桌面端走，不能拿来比版本。 */
        private val APK_NAME = Regex("^onepku-android-v(\\d+\\.\\d+\\.\\d+)\\.apk$")

        /** 随包带出去的 UA 也带上版本号，方便在 GitHub 侧看出是哪个客户端。 */
        val USER_AGENT: String = "OnePKU-Android/${BuildConfig.VERSION_NAME}"

        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private class GhAsset(
            val name: String = "",
            val size: Long = 0,
            val digest: String? = null,
            val browser_download_url: String = "",
        )

        /** assets 缺失说明响应不是预期的结构，宁可报错也不当成「没有更新」。 */
        @Serializable
        private class GhRelease(val assets: List<GhAsset>)

        fun apkVersionOf(name: String): String? =
            APK_NAME.matchEntire(name)?.groupValues?.get(1)

        fun compareVersions(a: String, b: String): Int {
            val x = a.segments() ?: return 0
            val y = b.segments() ?: return 0
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        fun isNewer(current: String, candidate: String): Boolean =
            compareVersions(candidate, current) > 0

        private fun String.segments(): List<Int>? {
            val parts = trim().trimStart('v').split('.')
            if (parts.isEmpty()) return null
            return parts.map { it.toIntOrNull() ?: return null }
        }

        /** 纯解析：认不出结构就抛，别把它当成「没有更新」。 */
        fun parseCheck(text: String, currentVersion: String): UpdateCheck {
            val release = runCatching { json.decodeFromString(GhRelease.serializer(), text) }
                .getOrElse { throw UpdateException("发布信息无法解析") }
            val packages = release.assets.mapNotNull { asset ->
                apkVersionOf(asset.name)?.let { it to asset }
            }
            if (packages.isEmpty()) return UpdateCheck.NoAndroidPackage
            val newer = packages.filter { isNewer(currentVersion, it.first) }
            if (newer.isEmpty()) return UpdateCheck.UpToDate
            val best = newer.reduce { acc, candidate ->
                if (compareVersions(candidate.first, acc.first) > 0) candidate else acc
            }
            val (version, asset) = best
            if (!isAssetUrl(asset.browser_download_url)) throw UpdateException("下载地址不在本仓库的 Releases 里")
            return UpdateCheck.Available(
                AndroidRelease(
                    version = version,
                    url = asset.browser_download_url,
                    size = asset.size,
                    sha256 = asset.digest?.removePrefix("sha256:")?.lowercase(),
                ),
            )
        }

        /** 只认本仓库 Releases 的下载地址；跟跳转由 HTTP 层负责，落地后另有校验和与签名两道关。 */
        fun isAssetUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val segments = parsed.pathSegments
            return parsed.scheme == "https" && parsed.host == "github.com" &&
                segments.size >= 6 && segments[2] == "releases" && segments[3] == "download" &&
                segments[0] == "Northodieart" && segments[1] == "onePKU"
        }

        fun digestMatches(expected: String?, actual: String): Boolean {
            val clean = expected?.removePrefix("sha256:")?.trim()?.lowercase() ?: return false
            return clean.isNotEmpty() && clean == actual.trim().lowercase()
        }

        fun signaturesMatch(apk: List<ByteArray>, installed: List<ByteArray>): Boolean =
            apk.any { candidate -> installed.any { it.contentEquals(candidate) } }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
