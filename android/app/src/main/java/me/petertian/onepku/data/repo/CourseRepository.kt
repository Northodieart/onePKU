package me.petertian.onepku.data.repo

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import me.petertian.onepku.core.session.Service
import me.petertian.onepku.data.auth.AuthManager
import me.petertian.onepku.data.course.Announcement
import me.petertian.onepku.data.course.AssignmentDetail
import me.petertian.onepku.data.course.AssignmentSummary
import me.petertian.onepku.data.course.Attachment
import me.petertian.onepku.data.course.ContentItem
import me.petertian.onepku.data.course.CourseApi
import me.petertian.onepku.data.course.CourseInfo
import me.petertian.onepku.data.course.FeedbackAttempt
import me.petertian.onepku.data.course.LearningGrade
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CourseRepository @Inject constructor(
    private val api: CourseApi,
    private val auth: AuthManager,
    @ApplicationContext private val context: Context,
) {
    private var coursesCache: CacheEntry<List<CourseInfo>>? = null
    private val assignmentsCache = mutableMapOf<String, CacheEntry<List<AssignmentSummary>>>()

    private suspend fun <T> run(block: suspend CourseApi.() -> T): T = withReauth(auth, Service.COURSE) {
        api.block()
    }

    suspend fun courses(forceRefresh: Boolean = false): List<CourseInfo> {
        coursesCache?.takeIf { !forceRefresh && it.fresh(TTL) }?.let { return it.data }
        return run { listCourses() }.also { coursesCache = CacheEntry(it) }
    }

    suspend fun announcements(courseId: String, courseName: String): List<Announcement> =
        run { listAnnouncements(courseId, courseName) }

    suspend fun content(courseId: String): List<ContentItem> = run { listAllContentRecursive(courseId) }

    suspend fun assignmentDetail(courseId: String, contentId: String): AssignmentDetail =
        run { getAssignment(courseId, contentId) }

    suspend fun attempts(courseId: String, contentId: String): List<FeedbackAttempt> =
        run { listAttempts(courseId, contentId) }

    suspend fun learningGrades(courseId: String): List<LearningGrade> = run { learningGrades(courseId) }

    /** 多门课作业汇总;逐门失败不拖垮整体。 */
    suspend fun assignments(courses: List<CourseInfo>, forceRefresh: Boolean = false): List<AssignmentSummary> =
        coroutineScope {
            val sem = Semaphore(3)
            courses.map { course ->
                async {
                    val cached = assignmentsCache[course.id]
                        ?.takeIf { !forceRefresh && it.fresh(TTL) }
                        ?.data
                    if (cached != null) {
                        cached
                    } else {
                        sem.acquire()
                        try {
                            val fresh = try {
                                run { listAssignmentsForCourse(course) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                emptyList()
                            }
                            fresh.also { assignmentsCache[course.id] = CacheEntry(it) }
                        } finally {
                            sem.release()
                        }
                    }
                }
            }.flatMap { it.await() }
        }

    /** 下载附件到 应用专属 Download/OnePKU/<courseName>/,返回文件。 */
    suspend fun download(courseName: String, attachment: Attachment): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "OnePKU/${sanitize(courseName)}",
        )
        val dest = File(dir, sanitize(attachment.name))
        if (dest.exists() && dest.length() > 0) return dest
        return run { downloadFile(attachment.url, dest) }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

    companion object {
        private const val TTL = 5 * 60 * 1000L
    }
}
