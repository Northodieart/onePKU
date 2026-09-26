package me.petertian.onepku.data.curriculum

import java.text.Normalizer

/**
 * 培养方案完成度计算。规则逐条对齐桌面端 src/lib/curriculum.ts,
 * 两边改了同一套规则时应当一起改。匹配不上的课程进入"待确认",不做猜测。
 */
object CurriculumEngine {

    const val IGNORE = "ignore"

    enum class CourseStatus { PASSED, FAILED, IN_PROGRESS, WITHDRAWN, OTHER }

    enum class MatchVia { NAME, ALTERNATIVE, VARIANT, KEYWORD, CATEGORY, OVERRIDE }

    data class MatchedCourse(
        val key: String,
        val name: String,
        val credits: Double?,
        val status: CourseStatus,
        val term: String,
        val category: String,
        val score: String,
        val via: MatchVia?,
        val sectionId: String?,
    )

    class Section(
        val id: String,
        val name: String,
        var requirement: String?,
        var min: Double?,
        var max: Double?,
        val unit: String?,
        var note: String? = null,
    ) {
        var earned = 0.0
        var inProgress = 0.0
        var passedCount = 0
        val courses = mutableListOf<MatchedCourse>()
        val children = mutableListOf<Section>()
    }

    data class Progress(
        val plan: Plan,
        val sections: List<Section>,
        val pending: List<MatchedCourse>,
        val ignored: List<MatchedCourse>,
        val required: Double?,
        val earned: Double,
        val inProgress: Double,
        val unknownCredits: Int,
        val usesRequirements: Boolean,
    )

    /** 成绩表里的一行;term 用于展示(如 "25-26·1"),year 用于推断入学年份(如 "25-26")。 */
    data class ScoreRow(
        val name: String,
        val credits: String,
        val score: String,
        val category: String,
        val term: String,
        val year: String,
    )

    /** 教学网课程;只有在读课程参与"在修"统计。 */
    data class CurrentCourse(val id: String, val name: String, val semester: String?, val current: Boolean)

    data class EnglishLevel(val id: String, val label: String, val credits: Int)

    /** 2025 版《北京大学大学英语课程培养方案》表 1;免修按同文件第 3 条获 2 学分。 */
    val ENGLISH_LEVELS = listOf(
        EnglishLevel("Y", "Y 级", 8),
        EnglishLevel("A", "A 级", 8),
        EnglishLevel("B", "B 级", 6),
        EnglishLevel("C", "C 级", 4),
        EnglishLevel("C+", "C+ 级", 2),
        EnglishLevel("exempt", "免修", 2),
    )
    const val ENGLISH_FULL_CREDITS = 8

    fun englishLevelInfo(level: String?): EnglishLevel? = ENGLISH_LEVELS.firstOrNull { it.id == level }

    // ---- 课程名规范化 ----

    private val ROMAN = mapOf(
        "Ⅰ" to "1", "Ⅱ" to "2", "Ⅲ" to "3", "Ⅳ" to "4", "Ⅴ" to "5", "Ⅵ" to "6",
        "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5", "VI" to "6",
    )
    private val CN_NUM = mapOf(
        "一" to "1", "二" to "2", "三" to "3", "四" to "4", "五" to "5", "六" to "6",
        "上" to "1", "下" to "2",
    )

    private val PARENS = Regex("[（(]([^（）()]*)[）)]")
    private val SEPARATORS = Regex("[\\s·・．.]")
    private val QUOTES = Regex("[“”\"'‘’]")

    /** 全角转半角、去空格、括号内罗马/中文序号转数字、统一大小写。 */
    fun normalizeCourseName(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase()
        s = PARENS.replace(s) { match ->
            val t = match.groupValues[1].trim()
            when {
                ROMAN.containsKey(t.uppercase()) -> "(${ROMAN[t.uppercase()]})"
                CN_NUM.containsKey(t) -> "(${CN_NUM[t]})"
                else -> "($t)"
            }
        }
        return s.replace(SEPARATORS, "").replace(QUOTES, "")
    }

    /** 去掉实验班、荣誉、班号等变体后缀,用于第二轮匹配。 */
    private val VARIANT_SUFFIX = Regex("\\((实验班|荣誉|honor|荣誉课程|英文班|国际班|双语)\\)")
    private val CLASS_NUMBER = Regex("\\(\\d+班\\)")
    private val TRAILING_VARIANT = Regex("(实验班|荣誉)$")

    fun variantBase(normalized: String): String = normalized
        .replace(VARIANT_SUFFIX, "")
        .replace(CLASS_NUMBER, "")
        .replace(TRAILING_VARIANT, "")

    private val DECIMAL = Regex("^\\d+(\\.\\d+)?$")

    fun decimal(value: String): Double? {
        val t = value.trim()
        return if (DECIMAL.matches(t)) t.toDoubleOrNull() else null
    }

    private val GRADE_LETTER = Regex("^[A-D][+-]?$")

    fun scoreStatus(score: String): CourseStatus {
        val s = score.trim().uppercase()
        decimal(s)?.let { return if (it >= 60) CourseStatus.PASSED else CourseStatus.FAILED }
        if (s in setOf("合格", "P", "EX", "通过", "优秀", "良好", "中等", "及格")) return CourseStatus.PASSED
        if (GRADE_LETTER.matches(s)) return CourseStatus.PASSED
        if (s in setOf("不合格", "NP", "F", "不及格")) return CourseStatus.FAILED
        if (s == "W") return CourseStatus.WITHDRAWN
        if (s.isEmpty() || s == "IP" || s == "I" || s == "未公布") return CourseStatus.IN_PROGRESS
        return CourseStatus.OTHER
    }

    // ---- 分区树与索引 ----

    private val PUBLIC_KEYWORDS: List<Pair<Regex, Regex>> = listOf(
        Regex("英语") to Regex("英语|大学英语"),
        Regex("思想政治理论必修|思政必修|思想政治理论课") to
            Regex("思想道德|马克思主义基本原理|毛泽东思想|习近平|近现代史纲要|形势与政策|思想政治"),
        Regex("选择性必修") to Regex("党史|新中国史|改革开放史|社会主义发展史|四史"),
        Regex("劳动") to Regex("劳动"),
        Regex("军事") to Regex("军事"),
        Regex("体育") to Regex(
            "^体育|体育|游泳|健美|武术|太极|瑜伽|篮球|足球|排球|羽毛球|乒乓|网球|跆拳|击剑|" +
                "体适能|舞蹈|攀岩|滑冰|棒垒|定向|素质拓展|健身|田径|龄球|高尔夫|桥牌|棋",
        ),
        Regex("信息课程|计算机") to Regex("计算概论|数据结构与算法|计算机实习|上机|问题求解|人工智能与计算思维"),
    )

    private class Index(
        val byName: LinkedHashMap<String, Hit>,
        val general: String?,
        val publicRoot: String?,
        val elective: String?,
        val free: String?,
        val publicChildren: List<Pair<Section, Regex>>,
        val flat: LinkedHashMap<String, Section>,
    )

    private data class Hit(val sectionId: String, val credits: Double?, val via: MatchVia)

    private fun section(id: String, name: String, requirement: String?, min: Double?, max: Double?, unit: String?, note: String? = null) =
        Section(id, name, requirement, min, max, unit, note)

    /** 把方案整理成两层学分系列,并建立课程名索引。 */
    private fun buildSections(plan: Plan): Triple<List<Section>, Index, Boolean> {
        val usesRequirements = plan.requirements.size >= 4
        val flat = LinkedHashMap<String, Section>()
        val sections = mutableListOf<Section>()
        val topNames = mapOf("1" to "公共基础课程", "2" to "专业必修课程", "3" to "选修课程")

        if (usesRequirements) {
            for (id in listOf("1", "2", "3")) {
                val top = plan.topRequirements.firstOrNull { it.id == id }
                val s = section(id, top?.name ?: topNames[id] ?: id, null, top?.min, top?.max, top?.unit)
                sections.add(s)
                flat[id] = s
            }
            for (r in plan.requirements) {
                val parent = flat[r.parent] ?: flat["1"] ?: continue
                val s = section(r.id, r.name, r.requirement, r.min, r.max, r.unit)
                parent.children.add(s)
                flat[r.id] = s
            }
        } else {
            for (g in plan.groups.filter { it.parent == null }) {
                val s = section(g.id, g.name, g.requirement, g.min, g.max, g.unit, g.note)
                sections.add(s)
                flat[g.id] = s
            }
            for (g in plan.groups.filter { it.parent != null }) {
                var parentId = g.parent!!
                while (parentId.isNotEmpty() && !flat.containsKey(parentId) && parentId.contains('.')) {
                    parentId = parentId.split('.').dropLast(1).joinToString(".")
                }
                val parent = flat[parentId] ?: flat[parentId.split(Regex("[.-]"))[0]]
                val s = section(g.id, g.name, g.requirement, g.min, g.max, g.unit, g.note)
                if (parent != null) parent.children.add(s) else sections.add(s)
                flat[g.id] = s
            }
        }

        fun findChild(re: Regex): Section? =
            flat.values.firstOrNull { it.children.isEmpty() && re.containsMatchIn(it.name) }

        val general = findChild(Regex("通识"))?.id
        val publicRoot = flat["1"]?.id
        val elective = findChild(Regex("专业选修"))?.id
        val free = findChild(Regex("自主选修|全校任选|任选"))?.id
        val publicChildren = (flat["1"]?.children ?: emptyList()).mapNotNull { s ->
            PUBLIC_KEYWORDS.firstOrNull { it.first.containsMatchIn(s.name) }?.let { s to it.second }
        }

        // 课程组 → 学分系列:a.b… → a-b;公共必修课表按关键词分到英语/思政/信息等。
        fun sectionForGroup(g: PlanGroup, courseName: String?): String? {
            if (!usesRequirements) return when {
                flat.containsKey(g.id) -> g.id
                g.parent != null && flat.containsKey(g.parent) -> g.parent
                else -> null
            }
            if (Regex("通识").containsMatchIn(g.name) && general != null) return general
            if (Regex("^1(\\.|$)").containsMatchIn(g.id) || Regex("公共必修").containsMatchIn(g.name)) {
                if (courseName != null) {
                    publicChildren.firstOrNull { it.second.containsMatchIn(courseName) }?.let { return it.first.id }
                }
                return publicRoot
            }
            val parts = g.id.split(Regex("[.-]"))
            for (n in minOf(parts.size, 2) downTo 2) {
                val rid = "${parts[0]}-${parts[1]}"
                if (flat.containsKey(rid)) return rid
            }
            return if (flat.containsKey(parts[0])) parts[0] else null
        }

        val byName = LinkedHashMap<String, Hit>()
        for (g in plan.groups) {
            for (c in g.courses) {
                val sectionId = sectionForGroup(g, c.name) ?: continue
                if (c.name.isEmpty()) continue
                val key = normalizeCourseName(c.name)
                if (!byName.containsKey(key)) byName[key] = Hit(sectionId, c.credits, MatchVia.NAME)
            }
            for (a in g.alternatives) {
                if (a.name.isEmpty()) continue
                val key = normalizeCourseName(a.name)
                if (byName.containsKey(key)) continue
                val replaced = a.replaces?.let { byName[normalizeCourseName(it)] }
                val sectionId = replaced?.sectionId ?: sectionForGroup(g, a.name) ?: continue
                byName[key] = Hit(sectionId, a.credits, MatchVia.ALTERNATIVE)
            }
        }
        return Triple(sections, Index(byName, general, publicRoot, elective, free, publicChildren, flat), usesRequirements)
    }

    /** 六级瀑布:手动归类 → 精确名 → 变体基名 → 反向变体 → 公共课关键词 → 课程类别。 */
    private fun assign(course: MatchedCourse, index: Index, overrides: Map<String, String>): MatchedCourse {
        val key = normalizeCourseName(course.name)
        overrides[key]?.let { override ->
            return course.copy(
                sectionId = if (override == IGNORE) IGNORE else override,
                via = MatchVia.OVERRIDE,
            )
        }
        index.byName[key]?.let {
            return course.copy(sectionId = it.sectionId, via = it.via, credits = course.credits ?: it.credits)
        }
        val base = variantBase(key)
        if (base != key) {
            index.byName[base]?.let {
                return course.copy(sectionId = it.sectionId, via = MatchVia.VARIANT, credits = course.credits ?: it.credits)
            }
        }
        for ((k, v) in index.byName) {
            if (variantBase(k) == key) {
                return course.copy(sectionId = v.sectionId, via = MatchVia.VARIANT, credits = course.credits ?: v.credits)
            }
        }
        if (!Regex("专业").containsMatchIn(course.category)) {
            index.publicChildren.firstOrNull { it.second.containsMatchIn(course.name) }?.let {
                return course.copy(sectionId = it.first.id, via = MatchVia.KEYWORD)
            }
        }
        val cat = course.category
        fun hit(target: String?) = course.copy(sectionId = target, via = MatchVia.CATEGORY)
        if (Regex("通选|通识").containsMatchIn(cat) && index.general != null) return hit(index.general)
        if (Regex("全校必修|公共必修").containsMatchIn(cat) && index.publicRoot != null) return hit(index.publicRoot)
        if (Regex("专业选修|限选").containsMatchIn(cat) && index.elective != null) return hit(index.elective)
        if (Regex("任选|自主").containsMatchIn(cat) && index.free != null) return hit(index.free)
        return course.copy(sectionId = null, via = null)
    }

    /** 按分级把"大学英语 2~8 学分"固定下来;不足 8 学分的差额按通识教育课计。 */
    private fun applyEnglishLevel(sections: List<Section>, index: Index, level: String) {
        val info = englishLevelInfo(level) ?: return
        val english = index.flat.values.firstOrNull {
            it.children.isEmpty() && Regex("大学英语|英语").containsMatchIn(it.name)
        } ?: return
        if (english.min == null) return
        val full = english.max ?: ENGLISH_FULL_CREDITS.toDouble()
        english.min = info.credits.toDouble()
        english.max = info.credits.toDouble()
        english.requirement = "${info.credits} 学分（${info.label}）"

        val shortfall = maxOf(0.0, full - info.credits)
        if (shortfall > 0) {
            val general = index.general?.let { index.flat[it] }
            if (general != null && general.min != null) {
                general.min = general.min!! + shortfall
                general.max = (general.max ?: general.min!! - shortfall) + shortfall
                general.requirement = "${fmt(general.min)} 学分（含补齐大学英语 ${fmt(shortfall)} 学分）"
                general.note = "方案允许用专业或通识选修补齐英语差额，这里按通识计"
            }
        }
        val top = sections.firstOrNull { english in it.children }
        if (top != null && top.min != null && top.max != null && top.min != top.max) {
            top.min = top.max
            top.requirement = "${fmt(top.max)} 学分"
        }
    }

    fun computeProgress(
        plan: Plan,
        scores: List<ScoreRow>,
        courses: List<CurrentCourse>,
        overrides: Map<String, String> = emptyMap(),
        englishLevel: String? = null,
    ): Progress {
        val (sections, index, usesRequirements) = buildSections(plan)
        if (!englishLevel.isNullOrEmpty() && usesRequirements) applyEnglishLevel(sections, index, englishLevel)

        val seen = HashSet<String>()
        val matched = mutableListOf<MatchedCourse>()
        scores.forEachIndexed { i, row ->
            seen.add(normalizeCourseName(row.name))
            matched.add(
                assign(
                    MatchedCourse(
                        key = "score:$i",
                        name = row.name,
                        credits = decimal(row.credits),
                        status = scoreStatus(row.score),
                        term = row.term,
                        category = row.category,
                        score = row.score,
                        via = null,
                        sectionId = null,
                    ),
                    index,
                    overrides,
                ),
            )
        }
        for (c in courses) {
            if (!c.current) continue
            val key = normalizeCourseName(c.name)
            if (!seen.add(key)) continue
            matched.add(
                assign(
                    MatchedCourse(
                        key = "course:${c.id}",
                        name = c.name,
                        credits = null,
                        status = CourseStatus.IN_PROGRESS,
                        term = c.semester ?: "本学期",
                        category = "在修",
                        score = "",
                        via = null,
                        sectionId = null,
                    ),
                    index,
                    overrides,
                ),
            )
        }

        val pending = mutableListOf<MatchedCourse>()
        val ignored = mutableListOf<MatchedCourse>()
        var unknownCredits = 0
        for (m in matched) {
            if (m.sectionId == IGNORE) { ignored.add(m); continue }
            if (m.status == CourseStatus.WITHDRAWN || m.status == CourseStatus.OTHER) { ignored.add(m); continue }
            val section = m.sectionId?.let { index.flat[it] }
            if (section == null) { pending.add(m); continue }
            section.courses.add(m)
            when (m.status) {
                CourseStatus.PASSED -> {
                    section.passedCount += 1
                    if (m.credits != null) section.earned += m.credits!! else unknownCredits += 1
                }
                CourseStatus.IN_PROGRESS ->
                    if (m.credits != null) section.inProgress += m.credits!! else unknownCredits += 1
                else -> Unit
            }
        }

        fun rollup(s: Section) {
            for (child in s.children) {
                rollup(child)
                s.earned += child.earned
                s.inProgress += child.inProgress
                s.passedCount += child.passedCount
            }
        }
        sections.forEach(::rollup)
        return Progress(
            plan = plan,
            sections = sections,
            pending = pending,
            ignored = ignored,
            required = plan.totalCredits?.min,
            earned = sections.sumOf { it.earned },
            inProgress = sections.sumOf { it.inProgress },
            unknownCredits = unknownCredits,
            usesRequirements = usesRequirements,
        )
    }

    /** 待确认可归入的目标:全部叶子系列。 */
    fun sectionChoices(progress: Progress): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (top in progress.sections) {
            if (top.children.isEmpty()) out += top.id to top.name
            for (child in top.children) out += child.id to "${top.name} · ${child.name}"
        }
        return out
    }

    // ---- 推断 ----

    data class Candidate(val id: String, val title: String, val school: String?, val matched: Int, val total: Int)

    data class Inference(
        val cohort: Int?,
        val version: Int?,
        val candidates: List<Candidate>,
        val evidence: List<String>,
        val narrowedBySchool: String?,
    )

    private val START_YEAR = Regex("^(\\d{2})-\\d{2}")

    private fun startYear(term: String): Int? =
        START_YEAR.find(term.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { 2000 + it }

    fun planVersions(index: List<PlanIndexEntry>): List<Int> =
        index.map { it.cohort }.distinct().sortedDescending()

    /** 入学年份对应的默认版本:不大于入学年份的最新版;没有则取最早版。 */
    fun defaultVersion(index: List<PlanIndexEntry>, cohort: Int?): Int? {
        val versions = planVersions(index)
        if (versions.isEmpty()) return null
        if (cohort == null) return versions.first()
        return versions.firstOrNull { it <= cohort } ?: versions.last()
    }

    /**
     * 从成绩与课程学期推断入学年份,再用专业必修课重合度排候选。
     * [department] 来自校内门户「单位」,命中时先把候选池限定到本院系,再按重合度排。
     */
    fun inferProfile(
        scores: List<ScoreRow>,
        courses: List<CurrentCourse>,
        index: List<PlanIndexEntry>,
        department: String? = null,
    ): Inference {
        val years = buildList {
            scores.forEach { startYear(it.year)?.let { y -> add(y) } }
            courses.forEach { c -> c.semester?.let { s -> startYear(s)?.let { y -> add(y) } } }
        }
        val cohort = years.minOrNull()
        val version = defaultVersion(index, cohort)
        val evidence = mutableListOf<String>()
        if (cohort != null) {
            evidence += "最早的成绩或课程学期是 ${cohort}-${(cohort + 1).toString().takeLast(2)} 学年，按此推断 ${cohort} 级"
        }
        if (cohort != null && version != null && version != cohort) {
            evidence += "没有 ${cohort} 版培养方案，默认使用 ${version} 版"
        }

        val taken = HashSet<String>()
        scores.forEach { taken += normalizeCourseName(it.name) }
        courses.forEach { taken += normalizeCourseName(it.name) }

        val pool = index.filter { it.kind != "project" && (version == null || it.cohort == version) }

        fun ranked(list: List<PlanIndexEntry>) = list.map { p ->
            val core = p.core.map { normalizeCourseName(it) }
            Candidate(
                id = p.id,
                title = p.title,
                school = p.school,
                matched = core.count { taken.contains(it) || taken.contains(variantBase(it)) },
                total = core.size,
            )
        }.filter { it.matched > 0 }
            .sortedWith(compareByDescending<Candidate> { it.matched }
                .thenByDescending { it.matched.toDouble() / maxOf(1, it.total) })
            .take(5)

        val school = pool.firstOrNull { sameSchool(it.school, department) }?.school
        var candidates = if (school != null) ranked(pool.filter { it.school == school }) else emptyList()
        if (candidates.isEmpty() && school != null) {
            evidence += "门户登记的院系是「$school」，但本院系没有重合的专业必修课，改按全校方案排序"
            candidates = ranked(pool)
        } else if (school != null) {
            evidence += "已按门户登记的院系「$school」限定候选范围"
        } else {
            candidates = ranked(pool)
        }

        if (candidates.isNotEmpty()) {
            evidence += "与「${candidates[0].title}」的专业必修课重合 ${candidates[0].matched} 门"
            val ties = candidates.drop(1).filter { it.matched == candidates[0].matched }
            if (ties.isNotEmpty()) {
                evidence += "「${ties.joinToString("」「") { it.title }}」重合门数相同，请核对是否选对了专业"
            }
        } else {
            evidence += "没有一门课与任何方案的专业必修课重合，请手动选择专业"
        }
        return Inference(cohort, version, candidates, evidence, school)
    }

    /** 门户「单位」与方案 school 字段的写法不完全一致(如"生命学院"与"生命科学学院")。 */
    private fun sameSchool(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        val ca = schoolCore(a)
        val cb = schoolCore(b)
        return ca.length >= 2 && cb.length >= 2 && (a.contains(b) || b.contains(a) || ca == cb || ca.startsWith(cb) || cb.startsWith(ca))
    }

    private fun schoolCore(name: String): String = name
        .removeSuffix("学院").removeSuffix("大学").removeSuffix("系").removeSuffix("研究所")

    /** 整数不带小数点,其他保留一位;与桌面端 fmt 一致。 */
    fun fmt(value: Double?): String {
        if (value == null) return "—"
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
    }
}
