package me.petertian.onepku.data.course

enum class ContentType { ASSIGNMENT, FOLDER, DOCUMENT }

data class Attachment(val name: String, val url: String)

data class CourseInfo(val id: String, val longTitle: String, val isCurrent: Boolean) {
    /** "26271-x: 数据结构与算法 (26-27学年第1学期)" → "数据结构与算法" */
    val name: String
        get() = longTitle.substringAfter(": ").substringBeforeLast(" (").ifBlank { longTitle }

    /** "26271-x: 数据结构与算法 (26-27学年第1学期)" → "26-27学年第1学期" */
    val semester: String
        get() = Regex("\\(([^()]*)\\)\\s*$").find(longTitle)?.groupValues?.get(1) ?: "其他"
}

data class CourseEntry(val name: String, val url: String)

data class ContentItem(
    val id: String,
    val title: String,
    val type: ContentType,
    val url: String?,
    val attachments: List<Attachment>,
    val description: String,
    val hasLink: Boolean,
)

data class Announcement(
    val id: String,
    val courseId: String,
    val courseName: String = "",
    val title: String,
    val bodyHtml: String,
    val date: String,
    val author: String,
)

data class AssignmentSummary(
    val courseId: String,
    val courseName: String,
    val contentId: String,
    val title: String,
    val deadlineRaw: String?,
    val deadlineEpochMs: Long?,
    val status: String,
)

data class AssignmentDetail(
    val title: String,
    val deadlineRaw: String?,
    val deadlineEpochMs: Long?,
    val instructions: String,
    val attachments: List<Attachment>,
    val status: String,
)

data class FeedbackAttempt(
    val id: String,
    val label: String,
    val score: String?,
    val pointsPossible: String?,
    val feedback: String?,
    val files: List<Attachment>,
    val url: String,
)

data class LearningGrade(
    val id: String,
    val title: String,
    val category: String,
    val score: String,
    val activity: String,
    val updated: String,
    val status: String,
)
