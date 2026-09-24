package me.petertian.onepku.data.course

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.petertian.onepku.core.network.CookieStores
import me.petertian.onepku.core.network.HttpFactory
import me.petertian.onepku.core.network.SessionExpiredException
import me.petertian.onepku.core.network.Ua
import me.petertian.onepku.core.session.Service
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/** 教学网 (Blackboard) 数据访问:几乎全部为 HTML,Jsoup 解析。 */
class CourseApi @Inject constructor(
    private val httpFactory: HttpFactory,
    private val cookieStores: CookieStores,
) {

    private fun client(): OkHttpClient =
        httpFactory.client(cookieJar = cookieStores.jar(Service.COURSE.key), ua = Ua.DESKTOP)

    private fun checkSession(resp: Response, body: String) {
        val url = resp.request.url
        if (url.host == "iaaa.pku.edu.cn" || url.encodedPath.contains("login")) {
            throw SessionExpiredException()
        }
        if (body.contains("name=\"loginForm\"") || body.contains("id=\"loginBox\"")) {
            throw SessionExpiredException()
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.code == 401) throw SessionExpiredException()
            if (!resp.isSuccessful) throw CourseApiException("请求失败: HTTP ${resp.code}")
            val body = resp.body.string()
            checkSession(resp, body)
            body
        }
    }

    private suspend fun getDoc(url: String): Document = Jsoup.parse(get(url), COURSE_BASE)

    // ---- 课程列表 ----

    suspend fun listCourses(): List<CourseInfo> = coroutineScope {
        val doc = getDoc("$COURSE_BASE/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_1_1")
        val keyRe = Regex("key=([\\d_]+),")
        val courses = mutableListOf<CourseInfo>()
        for (portlet in doc.select("div.portlet")) {
            val titleText = portlet.select("span.moduleTitle").first()?.text() ?: ""
            val isCurrent = titleText.contains("当前") || titleText.contains("Current Semester")
            for (ul in portlet.select("ul.courseListing")) {
                for (a in ul.select("li a")) {
                    val href = a.attr("href")
                    val key = keyRe.find(href)?.groupValues?.get(1) ?: continue
                    courses.add(CourseInfo(id = key, longTitle = a.text().trim(), isCurrent = isCurrent))
                }
            }
        }
        courses
    }

    // ---- 课程主页 / 侧边栏 ----

    private fun coursePageUrl(courseId: String) =
        "$COURSE_BASE/webapps/blackboard/execute/announcement?method=search&context=course_entry" +
            "&course_id=$courseId&handle=announcements_entry&mode=view"

    suspend fun listCourseEntries(courseId: String): List<CourseEntry> {
        val doc = getDoc(coursePageUrl(courseId))
        return doc.select("#courseMenuPalette_contents > li > a").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.attr("href")
            if (name.isEmpty() || href.isEmpty()) null else CourseEntry(name, href)
        }
    }

    // ---- 内容(资料/作业/文件夹)----

    suspend fun listContent(courseId: String, contentId: String): List<ContentItem> {
        val doc = getDoc(
            "$COURSE_BASE/webapps/blackboard/content/listContent.jsp?content_id=$contentId&course_id=$courseId"
        )
        return parseContentItems(doc)
    }

    private fun parseContentItems(doc: Document): List<ContentItem> {
        val items = mutableListOf<ContentItem>()
        for (li in doc.select("#content_listContainer > li, li.clearfix")) {
            val title = li.select("h3").first()?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue

            val link = li.select("h3 a").first()
            val url = link?.absUrl("href")?.ifEmpty { null }
            val id = li.attr("id").removePrefix("contentListItem").removePrefix(":")
                .ifEmpty {
                    url?.let { parseContentId(it) } ?: ""
                }

            val description = (li.select("div.vtbegenerated").first() ?: li.select("div.details").first())
                ?.text()?.trim().orEmpty()

            val attachments = li.select("ul.attachments li a").mapNotNull { a ->
                val name = a.text().trim().removePrefix(' ').trim()
                val href = a.absUrl("href")
                if (name.isEmpty() || href.isEmpty()) null else Attachment(name, href)
            }.toMutableList()

            val imgAlt = li.select("img").first()?.attr("alt").orEmpty()
            val type = when {
                imgAlt == "作业" || url?.contains("uploadAssignment") == true || url?.contains("assignment") == true ->
                    ContentType.ASSIGNMENT
                url?.contains("listContent") == true -> ContentType.FOLDER
                else -> ContentType.DOCUMENT
            }

            if (type == ContentType.DOCUMENT && url != null && url.contains("/bbcswebdav/")) {
                if (attachments.none { it.url == url }) attachments.add(Attachment(title, url))
            }

            items.add(
                ContentItem(
                    id = id,
                    title = title,
                    type = type,
                    url = url,
                    attachments = attachments,
                    description = description,
                    hasLink = link != null,
                )
            )
        }
        return items
    }

    /** 从侧边栏入口出发 BFS 递归收集全部内容。 */
    suspend fun listAllContentRecursive(courseId: String): List<ContentItem> = coroutineScope {
        val entries = listCourseEntries(courseId)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        for (entry in entries) {
            parseContentId(entry.url)?.let { cid ->
                if (visited.add(cid)) queue.add(cid)
            }
        }
        val all = mutableListOf<ContentItem>()
        val sem = Semaphore(4)
        while (queue.isNotEmpty()) {
            val batch = mutableListOf<String>()
            repeat(minOf(4, queue.size)) { batch.add(queue.removeFirst()) }
            val results = batch.map { cid ->
                async(Dispatchers.IO) { sem.withPermit { listContent(courseId, cid) } }
            }.map { it.await() }
            for (items in results) {
                for (item in items) {
                    if (item.type == ContentType.FOLDER && item.hasLink) {
                        item.url?.let { parseContentId(it) }?.let { cid ->
                            if (visited.add(cid)) queue.add(cid)
                        }
                    }
                    all.add(item)
                }
            }
        }
        all
    }

    private fun parseContentId(url: String): String? = runCatching {
        val abs = if (url.startsWith("http")) url else "$COURSE_BASE$url"
        abs.toHttpUrl().queryParameter("content_id")
    }.getOrNull()

    // ---- 公告 ----

    suspend fun listAnnouncements(courseId: String, courseName: String = ""): List<Announcement> {
        val doc = getDoc(coursePageUrl(courseId))
        return doc.select("#announcementList > li").mapNotNull { li ->
            val id = li.attr("id")
            val title = li.select("h3").first()?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val bodyHtml = li.select(".vtbegenerated").first()?.html().orEmpty()
            val details = li.select(".details").first()?.text().orEmpty()
            val date = details.substringAfter("发布时间:").substringBefore(" ").trim()
                .ifEmpty { details.trim() }
            val author = li.select(".announcementInfo").first()?.text()
                ?.substringAfter("发帖者:")?.trim().orEmpty()
            Announcement(id, courseId, courseName, title, bodyHtml, date, author)
        }
    }

    // ---- 作业 ----

    suspend fun getAssignment(courseId: String, contentId: String): AssignmentDetail {
        val doc = getDoc(assignmentUrl(courseId, contentId))
        val title = (doc.select("span.title").first() ?: doc.select("#pageTitleText").first())
            ?.text()?.trim().orEmpty()
        val deadlineRaw = doc.select(".itemdates").first()?.text()?.trim()
        val instructions = doc.select("div.vtbegenerated").first()?.text()?.trim().orEmpty()
        val attachments = doc.select("ul.attachments li a").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.absUrl("href")
            if (name.isEmpty() || href.isEmpty() || href.startsWith("javascript:") || href == "#") null
            else Attachment(name, href)
        }
        val status = doc.select(".status").first()?.text()?.trim() ?: "未知"
        return AssignmentDetail(title, deadlineRaw, parseDeadline(deadlineRaw), instructions, attachments, status)
    }

    /** 汇总一门课的全部作业(递归发现 + 逐个详情)。 */
    suspend fun listAssignmentsForCourse(course: CourseInfo): List<AssignmentSummary> = coroutineScope {
        val content = listAllContentRecursive(course.id)
        val assignments = content.filter { it.type == ContentType.ASSIGNMENT && it.id.isNotEmpty() }
        val sem = Semaphore(3)
        assignments.map { item ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching { getAssignment(course.id, item.id) }.getOrNull()?.let { detail ->
                        AssignmentSummary(
                            courseId = course.id,
                            courseName = course.name,
                            contentId = item.id,
                            title = detail.title.ifEmpty { item.title },
                            deadlineRaw = detail.deadlineRaw,
                            deadlineEpochMs = detail.deadlineEpochMs,
                            status = detail.status,
                        )
                    }
                }
            }
        }.mapNotNull { it.await() }
    }

    // ---- 作业反馈 / 提交历史 ----

    suspend fun listAttempts(courseId: String, contentId: String): List<FeedbackAttempt> = coroutineScope {
        val base = assignmentUrl(courseId, contentId)
        val doc = getDoc(base)
        val links = doc.select("#currentAttempt_attemptList a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val id = runCatching { href.toHttpUrl().queryParameter("attempt_id") }.getOrNull()
                if (id.isNullOrEmpty()) null else id to a.text().trim()
            }
            .distinctBy { it.first }
        if (links.isEmpty()) return@coroutineScope emptyList()

        val sem = Semaphore(3)
        links.map { (id, label) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val url = "$base&attempt_id=$id"
                    runCatching { parseAttemptPage(getDoc(url), id, label, url) }.getOrNull()
                }
            }
        }.mapNotNull { it.await() }
    }

    private fun parseAttemptPage(doc: Document, id: String, label: String, url: String): FeedbackAttempt {
        fun text(sel: String): String? = doc.select(sel).first()?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

        val score = text("#currentAttempt_grade")?.takeUnless { it == "-" || it == "—" }
        val points = text("#currentAttempt_pointsPossible")?.removePrefix("/")?.trim()
        val feedback = doc.select("#currentAttempt_feedback .vtbegenerated").first()?.text()?.trim()
        val files = doc.select("#currentAttempt_submissionList a.attachment[href]").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.absUrl("href")
            if (name.isEmpty() || href.isEmpty()) null else Attachment(name, href)
        }
        return FeedbackAttempt(id, label, score, points, feedback, files, url)
    }

    // ---- 教学网成绩 ----

    suspend fun learningGrades(courseId: String): List<LearningGrade> {
        val entry = listCourseEntries(courseId)
            .firstOrNull { it.name in listOf("个人成绩", "我的成绩", "My Grades") }
            ?: throw CourseApiException("本课程未开放个人成绩入口")
        val url = if (entry.url.startsWith("http")) entry.url else "$COURSE_BASE${entry.url}"
        val doc = getDoc(url)
        if (doc.select("#grades_wrapper").isEmpty()) {
            throw CourseApiException("未识别教学网成绩页面")
        }
        return doc.select("#grades_wrapper .sortable_item_row[role='row']").mapNotNull { row ->
            fun text(sel: String): String = row.select(sel).first()?.text()
                ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val id = row.attr("id").ifEmpty { return@mapNotNull null }
            val title = text(".gradable > a, .gradable > span")
            if (title.isEmpty()) return@mapNotNull null
            LearningGrade(
                id = id,
                title = title,
                category = text(".itemCat"),
                score = text(".cell.grade"),
                activity = text(".activityType"),
                updated = text(".lastActivityDate"),
                status = text(".gradeStatus"),
            )
        }
    }

    // ---- 下载 ----

    suspend fun downloadFile(url: String, dest: File): File = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw CourseApiException("下载失败: HTTP ${resp.code}")
            dest.parentFile?.mkdirs()
            resp.body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        }
    }

    companion object {
        const val COURSE_BASE = "https://course.pku.edu.cn"

        fun assignmentUrl(courseId: String, contentId: String) =
            "$COURSE_BASE/webapps/assignment/uploadAssignment?mode=view&content_id=$contentId&course_id=$courseId"

        /** 解析 Blackboard 中文截止时间 "2025年3月15日 星期六 下午11:59" → epoch millis(UTC+8)。 */
        fun parseDeadline(raw: String?): Long? {
            if (raw == null) return null
            val m = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*星期.\\s*(上午|下午)(\\d{1,2}):(\\d{1,2})")
                .find(raw) ?: return null
            val (year, month, day, ampm, hourStr, minuteStr) = m.destructured
            var hour = hourStr.toInt()
            if (ampm == "下午" && hour < 12) hour += 12
            if (ampm == "上午" && hour == 12) hour = 0
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            cal.clear()
            cal.set(year.toInt(), month.toInt() - 1, day.toInt(), hour, minuteStr.toInt())
            return cal.timeInMillis
        }
    }
}

class CourseApiException(message: String) : Exception(message)
