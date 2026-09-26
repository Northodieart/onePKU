package me.petertian.onepku.data.curriculum

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.petertian.onepku.data.curriculum.CurriculumEngine.CurrentCourse
import me.petertian.onepku.data.curriculum.CurriculumEngine.Inference
import me.petertian.onepku.data.curriculum.CurriculumEngine.Progress
import me.petertian.onepku.data.curriculum.CurriculumEngine.ScoreRow
import me.petertian.onepku.data.repo.CourseRepository
import me.petertian.onepku.data.repo.DepartmentStore
import me.petertian.onepku.data.repo.TreeholeRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 用户选定的方案与手动归类结果,只存本机。 */
@Serializable
data class CurriculumProfile(
    val cohort: Int? = null,
    val planId: String? = null,
    val secondaryPlanId: String? = null,
    val englishLevel: String? = null,
    val overrides: Map<String, String> = emptyMap(),
    val inferred: Boolean = false,
)

@Singleton
class CurriculumProfileStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("curriculum_profile", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CurriculumProfile> = _state.asStateFlow()

    fun current(): CurriculumProfile = _state.value

    fun save(profile: CurriculumProfile) {
        prefs.edit().putString(KEY, json.encodeToString(CurriculumProfile.serializer(), profile)).apply()
        _state.value = profile
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        _state.value = CurriculumProfile()
    }

    /** 把一门课归入某学分系列;sectionId 为 null 表示撤销归类,为 IGNORE 表示不计入。 */
    fun setOverride(courseName: String, sectionId: String?, secondaryPlanId: String? = null) {
        val key = CurriculumEngine.normalizeCourseName(courseName).let {
            if (secondaryPlanId.isNullOrEmpty()) it else "$secondaryPlanId:$it"
        }
        val next = current().overrides.toMutableMap()
        if (sectionId == null) next.remove(key) else next[key] = sectionId
        save(current().copy(overrides = next))
    }

    /** 双学位方案的归类键带方案前缀,取用时剥掉。 */
    fun overridesFor(planId: String?): Map<String, String> {
        val profile = current()
        val secondary = profile.secondaryPlanId
        return if (planId != null && planId == secondary) {
            val prefix = "$secondary:"
            profile.overrides.entries.filter { it.key.startsWith(prefix) }
                .associate { it.key.removePrefix(prefix) to it.value }
        } else {
            profile.overrides.filterKeys { !it.contains(":") || secondary == null || !it.startsWith("$secondary:") }
        }
    }

    private fun load(): CurriculumProfile = try {
        prefs.getString(KEY, null)?.let { json.decodeFromString(CurriculumProfile.serializer(), it) }
            ?: CurriculumProfile()
    } catch (e: Exception) {
        CurriculumProfile()
    }

    private companion object {
        const val KEY = "profile"
    }
}

@Singleton
class CurriculumRepository @Inject constructor(
    private val store: CurriculumStore,
    private val profiles: CurriculumProfileStore,
    private val treehole: TreeholeRepository,
    private val courseRepository: CourseRepository,
    private val departments: DepartmentStore,
) {
    val profile: CurriculumProfile get() = profiles.current()

    suspend fun scoreRows(): List<ScoreRow> = treehole.scores().entries.map {
        ScoreRow(
            name = it.name,
            credits = it.credit,
            score = it.score,
            category = it.category,
            term = "${it.year}·${it.term}",
            year = it.year,
        )
    }

    private suspend fun currentRows(): List<CurrentCourse> =
        courseRepository.courses().map {
            CurrentCourse(id = it.id, name = it.name, semester = it.semester, current = it.isCurrent)
        }

    /** 指定方案的完成度;成绩读取失败会抛出,由界面分块显示。 */
    suspend fun progress(planId: String): Pair<Plan, Progress> {
        val plan = store.plan(planId)
        val progress = CurriculumEngine.computeProgress(
            plan = plan,
            scores = scoreRows(),
            courses = currentRows(),
            overrides = profiles.overridesFor(planId),
            englishLevel = profiles.current().englishLevel,
        )
        return plan to progress
    }

    /** 首次进入时的推断:优先用门户「单位」把候选限定到本院系。 */
    suspend fun infer(): Inference {
        val scores = scoreRows()
        val courses = currentRows()
        return CurriculumEngine.inferProfile(
            scores = scores,
            courses = courses,
            index = store.index.plans,
            department = departments.current(),
        )
    }

    val plans: CurriculumStore get() = store
}
