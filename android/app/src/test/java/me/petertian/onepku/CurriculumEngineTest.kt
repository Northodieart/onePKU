package me.petertian.onepku

import me.petertian.onepku.data.curriculum.CreditRange
import me.petertian.onepku.data.curriculum.Plan
import me.petertian.onepku.data.curriculum.PlanAlternative
import me.petertian.onepku.data.curriculum.PlanCourse
import me.petertian.onepku.data.curriculum.PlanGroup
import me.petertian.onepku.data.curriculum.PlanIndexEntry
import me.petertian.onepku.data.curriculum.PlanRequirement
import me.petertian.onepku.data.curriculum.TopRequirement
import me.petertian.onepku.data.curriculum.CurriculumEngine
import me.petertian.onepku.data.curriculum.CurriculumEngine.CourseStatus
import me.petertian.onepku.data.curriculum.CurriculumEngine.CurrentCourse
import me.petertian.onepku.data.curriculum.CurriculumEngine.MatchVia
import me.petertian.onepku.data.curriculum.CurriculumEngine.Progress
import me.petertian.onepku.data.curriculum.CurriculumEngine.ScoreRow
import me.petertian.onepku.data.curriculum.CurriculumEngine.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 培养方案规则的单测:这些规则与桌面端 src/lib/curriculum.ts 一一对应。 */
class CurriculumEngineTest {

    private fun score(name: String, credits: String, mark: String, category: String, year: String = "24-25") =
        ScoreRow(name, credits, mark, category, "$year·1", year)

    /** 一份带要求树的合成方案:公共基础(英语/体育/思政) + 专业必修 + 通识。 */
    private fun plan() = Plan(
        id = "2025-测试院系-测试专业",
        cohort = 2025,
        major = "测试专业",
        title = "测试专业",
        totalCredits = CreditRange(144.0, 144.0),
        topRequirements = listOf(
            TopRequirement("1", "公共基础课程", 52.0, 58.0, "学分"),
            TopRequirement("2", "专业必修课程", 40.0, 40.0, "学分"),
            TopRequirement("3", "选修课程", 30.0, 40.0, "学分"),
        ),
        requirements = listOf(
            PlanRequirement("1-1", "1", "大学英语", "2～8 学分", 2.0, 8.0, "学分"),
            PlanRequirement("1-2", "1", "公共体育", "4 学分", 4.0, 4.0, "学分"),
            PlanRequirement("1-3", "1", "思想政治理论", "16 学分", 16.0, 16.0, "学分"),
            PlanRequirement("2-1", "2", "专业必修", "40 学分", 40.0, 40.0, "学分"),
            PlanRequirement("3-1", "3", "通识教育课", "12 学分", 12.0, 12.0, "学分"),
        ),
        groups = listOf(
            PlanGroup("1.1", "1", name = "大学英语", courses = listOf(PlanCourse(name = "英语基础A（一）", credits = 4.0))),
            PlanGroup("1.2", "1", name = "体育", courses = listOf(PlanCourse(name = "体育基础课", credits = 1.0))),
            PlanGroup(
                "2.1", "2", name = "专业必修",
                courses = listOf(
                    PlanCourse(name = "高等数学A（一）", credits = 5.0),
                    PlanCourse(name = "力学", credits = 3.0),
                    PlanCourse(name = "概率统计", credits = null),
                ),
            ),
            PlanGroup(
                "3.1", "3", name = "通识",
                courses = listOf(PlanCourse(name = "学科导论", credits = 2.0)),
                alternatives = listOf(PlanAlternative(name = "替代导论", credits = 2.0, replaces = "学科导论")),
            ),
        ),
    )

    /**
     * 仿 2025 物理学院-物理学:三大类总额只在课程组里写,专业核心课只按方向分列,
     * 要求树里既没有 2-2 也没有大类总额。
     */
    private fun physicsPlan() = Plan(
        id = "2025-物理学院-物理学",
        cohort = 2025,
        school = "物理学院",
        major = "物理学",
        title = "物理学",
        totalCredits = CreditRange(140.0, 152.0),
        requirements = listOf(
            PlanRequirement("1-1", "1", "公共必修课", "33～39 学分", 33.0, 39.0, "学分"),
            PlanRequirement("1-2", "1", "通识教育课", "12 学分", 12.0, 12.0, "学分"),
            PlanRequirement("2-1", "2", "专业基础课", "46 学分", 46.0, 46.0, "学分"),
            PlanRequirement("2-3", "2", "毕业论文", "6 学分", 6.0, 6.0, "学分"),
            PlanRequirement("3-1", "3", "专业选修课", "15 学分", 15.0, 15.0, "学分"),
        ),
        groups = listOf(
            PlanGroup("1", null, name = "公共基础课程", min = 45.0, max = 51.0, unit = "学分"),
            PlanGroup("2", null, name = "专业必修课程", min = 70.0, max = 76.0, unit = "学分"),
            PlanGroup("3", null, name = "选修课程", min = 25.0, max = 25.0, unit = "学分"),
            PlanGroup("2.1", "2", name = "专业基础课", min = 46.0, max = 46.0, unit = "学分"),
            PlanGroup("2.2", "2", name = "专业核心课"),
            PlanGroup(
                "2.2-1", "2.2", name = "物理学：24 学分", min = 24.0, max = 24.0, unit = "学分",
                courses = listOf(PlanCourse(name = "量子力学", credits = 4.0)),
            ),
        ),
    )

    private fun section(progress: Progress, id: String): Section =
        progress.sections.flatMap { listOf(it) + it.children }.first { it.id == id }    @Test
    fun `课程名规范化统一全角括号序号与空白`() {
        assertEquals("高等数学a(1)", CurriculumEngine.normalizeCourseName("高等数学A（一）"))
        assertEquals("高等数学a(1)", CurriculumEngine.normalizeCourseName("高等数学 A (Ⅰ) "))
        assertEquals("线性代数", CurriculumEngine.normalizeCourseName("线性 代 数"))
        assertEquals("大学物理(2)", CurriculumEngine.normalizeCourseName("大学物理（下）"))
        assertEquals("综合英语", CurriculumEngine.normalizeCourseName("综合“英语”"))
    }

    @Test
    fun `变体基名剥离实验班与班号`() {
        assertEquals("数据结构与算法", CurriculumEngine.variantBase("数据结构与算法(实验班)"))
        assertEquals("数学分析", CurriculumEngine.variantBase("数学分析(3班)"))
        assertEquals("线性代数", CurriculumEngine.variantBase("线性代数实验班"))
        assertEquals("线性代数", CurriculumEngine.variantBase("线性代数荣誉"))
    }

    @Test
    fun `成绩状态判定`() {
        assertEquals(CourseStatus.PASSED, CurriculumEngine.scoreStatus("85"))
        assertEquals(CourseStatus.FAILED, CurriculumEngine.scoreStatus("59"))
        listOf("合格", "P", "EX", "通过", "A", "B+", "C-", "优秀").forEach {
            assertEquals("对 $it", CourseStatus.PASSED, CurriculumEngine.scoreStatus(it))
        }
        listOf("不合格", "NP", "F", "不及格").forEach {
            assertEquals("对 $it", CourseStatus.FAILED, CurriculumEngine.scoreStatus(it))
        }
        assertEquals(CourseStatus.WITHDRAWN, CurriculumEngine.scoreStatus("W"))
        listOf("", "IP", "I", "未公布").forEach {
            assertEquals("对 '$it'", CourseStatus.IN_PROGRESS, CurriculumEngine.scoreStatus(it))
        }
        assertEquals(CourseStatus.OTHER, CurriculumEngine.scoreStatus("缓考"))
    }

    @Test
    fun `精确名匹配命中对应学分系列`() {
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("高等数学A（一）", "5", "90", "专业必修")), emptyList())
        val hit = section(p, "2-1").courses.single()
        assertEquals(MatchVia.NAME, hit.via)
        assertEquals(5.0, section(p, "2-1").earned, 0.001)
    }

    @Test
    fun `变体匹配在精确名失败后生效并回填方案学分`() {
        // 成绩单上没有学分,靠方案里的 5 分回填。
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("高等数学A（一）实验班", "", "88", "专业必修")), emptyList())
        val hit = section(p, "2-1").courses.single()
        assertEquals(MatchVia.VARIANT, hit.via)
        assertEquals(5.0, hit.credits!!, 0.001)
    }

    @Test
    fun `可选课程按被替代项归入同一系列`() {
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("替代导论", "2", "80", "通识选修")), emptyList())
        assertEquals(MatchVia.ALTERNATIVE, section(p, "3-1").courses.single().via)
    }

    @Test
    fun `公共课关键词匹配不作用于专业课`() {
        val sports = CurriculumEngine.computeProgress(plan(), listOf(score("太极拳", "1", "良好", "体育课")), emptyList())
        assertEquals(MatchVia.KEYWORD, section(sports, "1-2").courses.single().via)

        // 名称命中体育关键词,但类别是专业:不抢关键词,落到待确认。
        val major = CurriculumEngine.computeProgress(plan(), listOf(score("太极拳", "1", "良好", "专业必修")), emptyList())
        assertEquals("太极拳", major.pending.single().name)
    }

    @Test
    fun `课程类别兜底与未匹配进入待确认`() {
        val general = CurriculumEngine.computeProgress(plan(), listOf(score("人工智能伦理", "2", "85", "通识选修")), emptyList())
        assertEquals(MatchVia.CATEGORY, section(general, "3-1").courses.single().via)

        val unknown = CurriculumEngine.computeProgress(plan(), listOf(score("一门没听过的课", "2", "85", "其他")), emptyList())
        assertEquals(1, unknown.pending.size)
        assertNull(unknown.pending.single().sectionId)
    }

    @Test
    fun `手动归类优先于一切,不计入单独归档`() {
        val overrides = mapOf(
            CurriculumEngine.normalizeCourseName("一门没听过的课") to "2-1",
            CurriculumEngine.normalizeCourseName("学科导论") to CurriculumEngine.IGNORE,
        )
        val p = CurriculumEngine.computeProgress(
            plan(),
            listOf(score("一门没听过的课", "2", "85", "其他"), score("学科导论", "2", "90", "通识选修")),
            emptyList(),
            overrides,
        )
        assertEquals(MatchVia.OVERRIDE, section(p, "2-1").courses.single().via)
        assertEquals(0, p.pending.size)
        assertTrue(p.ignored.any { it.name == "学科导论" })
    }

    @Test
    fun `不及格课程保留在列表但不贡献学分`() {
        val p = CurriculumEngine.computeProgress(
            plan(),
            listOf(score("高等数学A（一）", "5", "40", "专业必修"), score("力学", "3", "70", "专业必修")),
            emptyList(),
        )
        val major = section(p, "2-1")
        assertEquals(2, major.courses.size)
        assertEquals(1, major.passedCount)
        assertEquals(3.0, major.earned, 0.001)
    }

    @Test
    fun `方案与成绩单都没有学分时计入未知学分,不静默当零`() {
        // 成绩单没学分,方案里这门课也没写学分。
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("概率统计", "", "85", "专业必修")), emptyList())
        assertEquals(1, p.unknownCredits)
        assertEquals(0.0, section(p, "2-1").earned, 0.001)
        assertEquals(1, section(p, "2-1").passedCount)
    }

    @Test
    fun `成绩单缺学分时按方案回填`() {
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("力学", "", "85", "专业必修")), emptyList())
        assertEquals(0, p.unknownCredits)
        assertEquals(3.0, section(p, "2-1").earned, 0.001)
    }

    @Test
    fun `在修课程单独列出且不计入统计`() {
        val p = CurriculumEngine.computeProgress(
            plan(),
            listOf(score("力学", "3", "80", "专业必修")),
            listOf(
                CurrentCourse("c1", "力学", "25-26 学年第 1 学期", true),
                CurrentCourse("c2", "高等数学A（一）", "25-26 学年第 1 学期", true),
            ),
        )
        val major = section(p, "2-1")
        // 在修的那门已与成绩表去重,只剩一门;它不进 courses,也不进 earned。
        assertEquals(1, major.courses.size)
        assertEquals(1, major.inProgressCourses.size)
        assertEquals("高等数学A（一）", major.inProgressCourses.single().name)
        assertEquals(3.0, major.earned, 0.001)
        assertEquals(1, major.passedCount)
        assertEquals(0, p.unknownCredits)
    }

    @Test
    fun `成绩未公布的课也不计入统计`() {
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("力学", "3", "未公布", "专业必修")), emptyList())
        val major = section(p, "2-1")
        assertEquals(0, major.courses.size)
        assertEquals(1, major.inProgressCourses.size)
        assertEquals(0.0, major.earned, 0.001)
        assertEquals(0, p.unknownCredits)
    }

    @Test
    fun `父级汇总子级学分`() {
        val p = CurriculumEngine.computeProgress(plan(), listOf(score("高等数学A（一）", "5", "90", "专业必修")), emptyList())
        assertEquals(5.0, section(p, "2").earned, 0.001)
        assertEquals(5.0, p.earned, 0.001)
    }

    @Test
    fun `英语分级锁定学分并把差额补进通识`() {
        val p = CurriculumEngine.computeProgress(plan(), emptyList(), emptyList(), englishLevel = "C")
        val english = section(p, "1-1")
        assertEquals(4.0, english.min!!, 0.001)
        assertEquals(4.0, english.max!!, 0.001)
        assertEquals("4 学分（C 级）", english.requirement)

        val general = section(p, "3-1")
        assertEquals(16.0, general.min!!, 0.001)   // 12 + 差额 4
        assertEquals(16.0, general.max!!, 0.001)
        assertTrue(general.requirement!!.contains("含补齐大学英语 4 学分"))

        // 公共基础大类原本是 52~58 的区间,英语定级后按上限固定。
        assertEquals(58.0, section(p, "1").min!!, 0.001)
    }

    @Test
    fun `免修拿不到英语学分,差额 8 学分补进通识`() {
        val p = CurriculumEngine.computeProgress(plan(), emptyList(), emptyList(), englishLevel = "exempt")
        assertEquals(0.0, section(p, "1-1").min!!, 0.001)
        assertEquals(20.0, section(p, "3-1").min!!, 0.001)
    }

    @Test
    fun `C 级与 C+ 级在分级表里`() {
        assertEquals(4, CurriculumEngine.englishLevelInfo("C")?.credits)
        assertEquals(2, CurriculumEngine.englishLevelInfo("C+")?.credits)
        assertEquals(0, CurriculumEngine.englishLevelInfo("exempt")?.credits)
    }

    @Test
    fun `方案没写大类总额时退回课程组的学分`() {
        val p = CurriculumEngine.computeProgress(physicsPlan(), emptyList(), emptyList())
        assertEquals(45.0, section(p, "1").min!!, 0.001)
        assertEquals(70.0, section(p, "2").min!!, 0.001)
        assertEquals(25.0, section(p, "3").min!!, 0.001)
    }

    @Test
    fun `要求表缺失的子系列按课程组补回,总额取大类余额`() {
        val p = CurriculumEngine.computeProgress(physicsPlan(), emptyList(), emptyList())
        val core = section(p, "2-2")
        assertEquals("专业核心课", core.name)
        // 专业必修 70 学分,专业基础课 46 + 毕业论文 6,剩下的都归专业核心课。
        assertEquals(18.0, core.min!!, 0.001)
        assertEquals(24.0, core.max!!, 0.001)
        assertEquals("18～24 学分", core.requirement)

        val hit = CurriculumEngine.computeProgress(
            physicsPlan(), listOf(score("量子力学", "4", "90", "专业必修")), emptyList(),
        )
        // 方向课表挂在 2.2-1 下,课程应落到补出来的 2-2,而不是堆在大类本身。
        assertEquals(4.0, section(hit, "2-2").earned, 0.001)
        assertEquals(4.0, section(hit, "2").earned, 0.001)
        assertTrue(section(hit, "2").courses.isEmpty())
    }

    @Test
    fun `没有要求表的方案退回分组树`() {
        val groupPlan = plan().copy(requirements = emptyList(), topRequirements = emptyList())
        val p = CurriculumEngine.computeProgress(groupPlan, listOf(score("高等数学A（一）", "5", "90", "专业必修")), emptyList())
        assertFalse(p.usesRequirements)
        assertEquals(5.0, section(p, "2.1").earned, 0.001)
    }

    @Test
    fun `版本回退到不晚于入学年份的最新版`() {
        assertEquals(2021, CurriculumEngine.defaultVersion(index(), 2022))
        assertEquals(2021, CurriculumEngine.defaultVersion(index(), 2019))
        assertEquals(2025, CurriculumEngine.defaultVersion(index(), null))
        assertEquals(2025, CurriculumEngine.defaultVersion(index(), 2025))
    }

    @Test
    fun `推断按重合门数排序,平手时比重合率并提示核对`() {
        val scores = listOf(
            score("高等数学A（一）", "5", "90", "专业必修", "25-26"),
            score("力学", "3", "85", "专业必修", "25-26"),
            score("线性代数", "3", "88", "专业必修", "25-26"),
        )
        val inferred = CurriculumEngine.inferProfile(scores, emptyList(), index())
        assertEquals(2025, inferred.cohort)
        assertEquals("甲-窄口径", inferred.candidates.first().id)
        assertEquals(2, inferred.candidates.first().matched)
        assertEquals(2, inferred.candidates.first().total)
        assertTrue(inferred.evidence.any { it.contains("重合门数相同") })
    }

    @Test
    fun `门户院系命中时先限定候选范围`() {
        val scores = listOf(score("高等数学A（一）", "5", "90", "专业必修", "25-26"))
        val inferred = CurriculumEngine.inferProfile(scores, emptyList(), index(), department = "院系甲")
        assertEquals("院系甲", inferred.narrowedBySchool)
        assertTrue(inferred.candidates.isNotEmpty())
        assertTrue(inferred.candidates.all { it.school == "院系甲" })
        assertTrue(inferred.evidence.any { it.contains("限定候选范围") })
    }

    @Test
    fun `本院系没有重合时退回全校并说明原因`() {
        val scores = listOf(score("只有院系乙开的课", "2", "90", "专业必修", "25-26"))
        val inferred = CurriculumEngine.inferProfile(scores, emptyList(), index(), department = "院系甲")
        assertTrue(inferred.candidates.all { it.school == "院系乙" })
        assertTrue(inferred.evidence.any { it.contains("改按全校方案排序") })
    }

    @Test
    fun `零重合不猜专业`() {
        val scores = listOf(score("一门谁都不在方案里的课", "2", "90", "任选", "24-25"))
        val inferred = CurriculumEngine.inferProfile(scores, emptyList(), index())
        assertTrue(inferred.candidates.isEmpty())
        assertEquals(2024, inferred.cohort)
        assertEquals(2021, inferred.version)
        assertTrue(inferred.evidence.any { it.contains("请手动选择专业") })
    }

    @Test
    fun `真实方案数据里物理学院的专业核心课能补齐`() {
        // 这份是随应用打包的离线数据,路径随 Gradle 工作目录变化,找不到就跳过。
        val file = listOf(
            "../../data/curriculum/2025/2025-物理学院-物理学.json",
            "../data/curriculum/2025/2025-物理学院-物理学.json",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("离线方案数据不在工作目录里", file != null)
        val real = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Plan.serializer(), file!!.readText())
        val coreCourse = real.groups.first { it.id == "2.2-1" }.courses.first().name
        val p = CurriculumEngine.computeProgress(
            real, listOf(score(coreCourse, "4", "90", "专业必修")), emptyList(),
        )

        assertEquals(listOf("1", "2", "3"), p.sections.map { it.id })
        assertEquals(45.0, section(p, "1").min!!, 0.001)
        assertEquals(70.0, section(p, "2").min!!, 0.001)
        assertEquals(listOf("2-1", "2-2", "2-3"), section(p, "2").children.map { it.id })
        assertEquals("专业核心课", section(p, "2-2").name)
        assertEquals(18.0, section(p, "2-2").min!!, 0.001)
        // 方向课表挂在 2.2-1 下,课程要归到补出来的 2-2,不能散在大类本身。
        assertEquals(4.0, section(p, "2-2").earned, 0.001)
        assertTrue(section(p, "2").courses.isEmpty())
    }

    private fun index(): List<PlanIndexEntry> = listOf(
        PlanIndexEntry(
            id = "甲-窄口径", cohort = 2025, school = "院系甲", major = "窄口径", title = "窄口径专业",
            core = listOf("高等数学A（一）", "力学"), file = "2025/甲-窄口径.json",
        ),
        PlanIndexEntry(
            id = "甲-宽口径", cohort = 2025, school = "院系甲", major = "宽口径", title = "宽口径专业",
            core = listOf("高等数学A（一）", "力学", "概率论", "复变函数", "偏微分方程"), file = "2025/甲-宽口径.json",
        ),
        PlanIndexEntry(
            id = "乙-乙专业", cohort = 2025, school = "院系乙", major = "乙", title = "乙专业",
            core = listOf("高等数学A（一）", "只有院系乙开的课"), file = "2025/乙-乙专业.json",
        ),
        PlanIndexEntry(
            id = "旧版-丙专业", cohort = 2021, school = "院系丙", major = "丙", title = "丙专业",
            core = listOf("高等数学A（一）"), file = "2021/丙-丙专业.json",
        ),
        PlanIndexEntry(
            id = "甲-项目制", cohort = 2025, school = "院系甲", major = "项目", title = "项目制",
            kind = "project", core = listOf("高等数学A（一）", "力学"), file = "2025/甲-项目制.json",
        ),
    )
}
