package me.petertian.onepku

import java.io.File
import me.petertian.onepku.data.update.UpdateApi
import me.petertian.onepku.data.update.UpdateCheck
import me.petertian.onepku.data.update.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内检查更新的判读逻辑。两条底线：Release 的标签跟着桌面端走，安卓版本只能从包名里认；
 * 「有新版但这一版没发安卓包」不能被说成「已是最新」，否则会一直静默不更新。
 */
class UpdateApiTest {

    private fun releaseJson(vararg names: String) =
        """{"tag_name":"v0.12.5","assets":[""" + names.joinToString(",") { name ->
            """{"name":"$name","size":22743250,"digest":"sha256:ff3dea54616249ff233f4a805d8dcfe456fbb15f13c448324f77d866976ea7a7",""" +
                """"browser_download_url":"https://github.com/Northodieart/onePKU/releases/download/v0.12.5/$name"}"""
        } + "]}"

    @Test
    fun `只认 onepku-android 包名里的版本号，桌面资产一概跳过`() {
        val check = UpdateApi.parseCheck(
            releaseJson("OnePKU.app.zip", "latest.json", "onepku-android-v0.4.0.apk"),
            currentVersion = "0.3.2",
        )
        val available = check as UpdateCheck.Available
        assertEquals("0.4.0", available.release.version)
        assertEquals(22743250L, available.release.size)
        assertEquals(
            "ff3dea54616249ff233f4a805d8dcfe456fbb15f13c448324f77d866976ea7a7",
            available.release.sha256,
        )
        assertTrue(available.release.url.endsWith("/onepku-android-v0.4.0.apk"))
    }

    @Test
    fun `桌面版本号比安卓大，也不代表安卓有更新`() {
        // 标签 v0.12.5 与包名里的 0.3.2 各走各的序列，这里当前已是最新。
        val check = UpdateApi.parseCheck(releaseJson("onepku-android-v0.3.2.apk"), "0.3.2")
        assertEquals(UpdateCheck.UpToDate, check)
    }

    @Test
    fun `没有安卓安装包时说清楚，不冒充已是最新`() {
        val check = UpdateApi.parseCheck(releaseJson("OnePKU.app.zip", "latest.json"), "0.3.2")
        assertEquals(UpdateCheck.NoAndroidPackage, check)
    }

    @Test
    fun `多个安卓包取版本号最高的那个，按数值而不是字典序比`() {
        val check = UpdateApi.parseCheck(
            releaseJson("onepku-android-v0.10.0.apk", "onepku-android-v0.9.0.apk"),
            currentVersion = "0.3.2",
        ) as UpdateCheck.Available
        assertEquals("0.10.0", check.release.version)
    }

    @Test
    fun `版本号认不出就当没有更新，不拿垃圾去比`() {
        assertNull(UpdateApi.apkVersionOf("onepku-android.apk"))
        assertNull(UpdateApi.apkVersionOf("onepku-android-v0.4.apk"))
        assertNull(UpdateApi.apkVersionOf("OnePKU_0.12.5_x64-setup.exe"))
        assertFalse(UpdateApi.isNewer("0.3.2", "beta"))
        assertEquals(0, UpdateApi.compareVersions("0.3.2", "not-a-version"))
    }

    @Test
    fun `结构认不出就抛，不静默当成没有更新`() {
        runCatching { UpdateApi.parseCheck("""{"tag_name":"v1"}""", "0.3.2") }
            .onSuccess { error("没有 assets 应该报错，而不是当成没有更新") }
            .onFailure { assertEquals(UpdateException::class, it::class) }
        runCatching { UpdateApi.parseCheck("not json", "0.3.2") }
            .onFailure { assertEquals(UpdateException::class, it::class) }
    }

    @Test
    fun `下载地址必须还在本仓库的 Releases 里`() {
        assertTrue(
            UpdateApi.isAssetUrl(
                "https://github.com/Northodieart/onePKU/releases/download/v0.12.5/onepku-android-v0.4.0.apk",
            ),
        )
        assertFalse(UpdateApi.isAssetUrl("http://github.com/Northodieart/onePKU/releases/download/v1/a.apk"))
        assertFalse(UpdateApi.isAssetUrl("https://evil.example/Northodieart/onePKU/releases/download/v1/a.apk"))
        assertFalse(UpdateApi.isAssetUrl("https://github.com/someone/else/releases/download/v1/a.apk"))
    }

    @Test
    fun `校验和比较容忍 sha256 前缀与大小写`() {
        val hex = "AB34"
        assertTrue(UpdateApi.digestMatches("sha256:ab34", hex))
        assertTrue(UpdateApi.digestMatches("ab34", "  AB34 "))
        assertFalse(UpdateApi.digestMatches(null, hex))
        assertFalse(UpdateApi.digestMatches("sha256:", hex))
        assertFalse(UpdateApi.digestMatches("sha256:ff00", hex))
    }

    @Test
    fun `签名比对要求与本机安装的是同一把钥匙`() {
        val key = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        assertTrue(UpdateApi.signaturesMatch(listOf(key), listOf(other, key)))
        assertFalse(UpdateApi.signaturesMatch(listOf(key), listOf(other)))
        assertFalse(UpdateApi.signaturesMatch(emptyList(), listOf(key)))
        assertFalse(UpdateApi.signaturesMatch(listOf(key), emptyList()))
    }

    @Test
    fun `校验和算的是文件内容，能挡住半截下载`() {
        val file = File.createTempFile("onepku-digest", ".bin").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        assertEquals(
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            UpdateApi.sha256Of(file),
        )
    }
}
