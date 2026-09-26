package me.petertian.onepku.ui.curriculum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.petertian.onepku.data.curriculum.CurriculumEngine
import me.petertian.onepku.data.curriculum.CurriculumEngine.MatchedCourse
import me.petertian.onepku.data.curriculum.CurriculumEngine.Progress
import me.petertian.onepku.data.curriculum.CurriculumEngine.Section
import me.petertian.onepku.data.curriculum.CurriculumProfileStore
import me.petertian.onepku.data.curriculum.CurriculumRepository
import me.petertian.onepku.data.curriculum.Plan
import me.petertian.onepku.ui.components.ErrorBox
import me.petertian.onepku.ui.components.LoadingBox
import me.petertian.onepku.ui.components.UiData
import me.petertian.onepku.ui.navigation.Routes
import me.petertian.onepku.ui.navigation.back
import kotlin.math.roundToInt
import javax.inject.Inject

data class CurriculumUiState(
    val content: UiData<Pair<Plan, Progress>> = UiData.Loading,
    val planTitle: String = "",
    val hasProfile: Boolean = false,
    val choosingFor: String? = null,
)

@HiltViewModel
class CurriculumViewModel @Inject constructor(
    private val repo: CurriculumRepository,
    private val profiles: CurriculumProfileStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(CurriculumUiState())
    val ui: StateFlow<CurriculumUiState> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        val planId = profiles.current().planId
        if (planId.isNullOrEmpty()) {
            _ui.update { it.copy(hasProfile = false, content = UiData.Loading, planTitle = "") }
            return
        }
        _ui.update { it.copy(hasProfile = true, content = UiData.Loading) }
        viewModelScope.launch {
            // index.json 有 700 多 KB,读盘与方案解析都放 IO 线程。
            val title = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.plans.entry(planId)?.title.orEmpty()
            }
            _ui.update {
                it.copy(
                    planTitle = title,
                    content = try {
                        UiData.Ready(repo.progress(planId))
                    } catch (e: Exception) {
                        UiData.Failure(e.message ?: "培养方案读取失败")
                    },
                )
            }
        }
    }

    /** 把一门课归入某学分系列;sectionId 为 IGNORE 表示不计入,null 表示撤销归类。 */
    fun classify(courseName: String, sectionId: String?) {
        profiles.setOverride(courseName, sectionId)
        closeChooser()
        reload()
    }

    fun openChooser(name: String) = _ui.update { it.copy(choosingFor = name) }

    fun closeChooser() = _ui.update { it.copy(choosingFor = null) }

    fun choices(): List<Pair<String, String>> =
        (_ui.value.content as? UiData.Ready)?.let { CurriculumEngine.sectionChoices(it.value.second) } ?: emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurriculumScreen(nav: NavHostController, vm: CurriculumViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()

    // 从"选择方案"页返回时重新计算完成度。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ui.choosingFor?.let { name ->
        ClassifierDialog(
            courseName = name,
            choices = vm.choices(),
            onPick = { sectionId -> vm.classify(name, sectionId) },
            onDismiss = vm::closeChooser,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.planTitle.ifBlank { "培养方案" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.CURRICULUM_PROFILE) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "选择方案")
                    }
                },
            )
        },
    ) { padding ->
        when (val data = ui.content) {
            is UiData.Loading -> if (ui.hasProfile) LoadingBox(message = "方案读取中…") else NoProfile(padding, nav)
            is UiData.Failure -> ErrorBox(data.message, onRetry = vm::reload)
            is UiData.Ready -> ProgressPager(data.value.second, padding, vm)
        }
    }
}

@Composable
private fun NoProfile(padding: PaddingValues, nav: NavHostController) {
    Column(
        Modifier.padding(padding).fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("尚未选择培养方案", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "会根据成绩与在修课程推断年级和专业,也可以自己选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { nav.navigate(Routes.CURRICULUM_PROFILE) }) { Text("开始选择") }
    }
}

@Composable
private fun ProgressPager(progress: Progress, padding: PaddingValues, vm: CurriculumViewModel) {
    // 第一页毕业总学分,之后每个大类一页,最后按需确认页收尾。
    val titles = buildList {
        add("毕业总学分")
        progress.sections.forEach { add(it.name) }
        if (progress.pending.isNotEmpty() || progress.ignored.isNotEmpty()) add("待确认与不计入")
    }
    val pagerState = rememberPagerState(pageCount = { titles.size })

    Column(Modifier.padding(padding).fillMaxSize()) {
        Text(
            titles.getOrElse(pagerState.currentPage) { "" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        PageIndicator(pagerState.currentPage, titles.size)
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when {
                page == 0 -> TotalPage(progress)
                page <= progress.sections.size -> SectionPage(progress.sections[page - 1], vm)
                else -> PendingPage(progress, vm)
            }
        }
    }
}

/** 灰色横条 + 随页面滑动的主题色短线。 */
@Composable
private fun PageIndicator(index: Int, count: Int) {
    if (count <= 1) return
    var trackWidth by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val segment = if (count > 0) trackWidth / count else 0f
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp)
            .onSizeChanged { trackWidth = it.width.toFloat() }
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
    ) {
        Box(
            Modifier
                .offset { IntOffset((segment * index + segment * 0.2f).roundToInt(), 0) }
                .width(with(density) { ((segment * 0.6f).coerceAtLeast(20f)).toDp() })
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

@Composable
private fun TotalPage(progress: Progress) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { CurriculumRing(progress.earned, progress.inProgress, progress.required) }
        item {
            Text(
                "已获 ${CurriculumEngine.fmt(progress.earned)} / " +
                    (progress.required?.let { "${CurriculumEngine.fmt(it)} 学分" } ?: "未写明总学分") +
                    (if (progress.inProgress > 0) " · 在修 ${CurriculumEngine.fmt(progress.inProgress)}" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (progress.required == null) {
            item {
                Text(
                    "这份方案没有明确写出毕业总学分,因此只显示已获学分,不显示完成比例。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (progress.unknownCredits > 0) {
            item {
                Text(
                    "${progress.unknownCredits} 门课的学分未知,未计入合计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("各大类", style = MaterialTheme.typography.titleSmall)
                    progress.sections.forEach {
                        val (value, pending) = measure(it)
                        SectionBar(it, value, pending)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionPage(section: Section, vm: CurriculumViewModel) {
    val (value, pending) = measure(section)
    val gap = gapOf(section, value, pending)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CurriculumRing(
                    value = if (section.unit == "门") section.passedCount.toDouble() else section.earned,
                    pending = pending,
                    target = section.min,
                    diameter = 96.dp,
                    strokeWidth = 9.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "${CurriculumEngine.fmt(value)} / ${section.min?.let { CurriculumEngine.fmt(it) } ?: "—"} ${section.unit ?: "学分"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    gap?.let {
                        Text(
                            if (it <= 0.0) "已满足" else "还差 ${CurriculumEngine.fmt(it)} ${section.unit ?: "学分"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it <= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    section.requirement?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        section.note?.let {
            item {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (section.children.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("子系列", style = MaterialTheme.typography.titleSmall)
                        section.children.forEach { child ->
                            val (cv, cp) = measure(child)
                            SectionBar(child, cv, cp)
                        }
                    }
                }
            }
        }
        if (section.courses.isNotEmpty()) {
            item { Text("已计入课程 ${section.courses.size} 门", style = MaterialTheme.typography.titleSmall) }
            items(section.courses, key = { it.key }) { CourseRow(it) }
        } else {
            item {
                Text(
                    "这一类还没有计入的课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingPage(progress: Progress, vm: CurriculumViewModel) {
    var showIgnored by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "这些课程没能对上学分系列。归入某一类,或标记为不计入;判断不了就留着,不猜。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress.pending.isEmpty()) {
            item { Text("没有待确认的课程", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(progress.pending, key = { it.key }) { course ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(course.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOf(
                                course.term, course.category, course.score.ifBlank { "在修" },
                                course.credits?.let { "${CurriculumEngine.fmt(it)} 学分" } ?: "学分未知",
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.openChooser(course.name) }) { Text("归入…") }
                            TextButton(onClick = { vm.classify(course.name, CurriculumEngine.IGNORE) }) { Text("不计入") }
                        }
                    }
                }
            }
        }
        if (progress.ignored.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { showIgnored = !showIgnored }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("不计入 ${progress.ignored.size} 门", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (showIgnored) "收起" else "展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (showIgnored) {
                items(progress.ignored, key = { "ignored-${it.key}" }) { course ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(course.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.classify(course.name, null) }) { Text("恢复") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClassifierDialog(
    courseName: String,
    choices: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把这门课归入") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(choices) { (id, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionBar(section: Section, value: Double, pending: Double) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(section.name, style = MaterialTheme.typography.bodySmall)
            Text(
                "${CurriculumEngine.fmt(value)} / ${section.min?.let { CurriculumEngine.fmt(it) } ?: "—"}" +
                    (section.unit?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val target = section.min
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { (((value + pending) / target).toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
    }
}

@Composable
private fun CourseRow(course: MatchedCourse) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    listOf(
                        course.term,
                        course.score.ifBlank { "在修" },
                        course.credits?.let { "${CurriculumEngine.fmt(it)} 学分" },
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(statusLabel(course), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun statusLabel(course: MatchedCourse): String = when (course.status) {
    CurriculumEngine.CourseStatus.IN_PROGRESS -> "在修"
    CurriculumEngine.CourseStatus.PASSED -> "已获"
    CurriculumEngine.CourseStatus.FAILED -> "未通过"
    CurriculumEngine.CourseStatus.WITHDRAWN -> "退课"
    CurriculumEngine.CourseStatus.OTHER -> "其他"
}

/** 单位决定度量:按门数、按学时还是按学分。 */
private fun measure(section: Section): Pair<Double, Double> =
    if (section.unit == "门") section.passedCount.toDouble() to 0.0
    else section.earned to section.inProgress

private fun gapOf(section: Section, value: Double, pending: Double): Double? {
    val min = section.min ?: return null
    if (section.unit == "学时") return null
    return maxOf(0.0, min - value - pending)
}

/** 圆环:已获为实色弧,在修为淡色弧延伸,达标换成功色。 */
@Composable
private fun CurriculumRing(
    value: Double,
    pending: Double,
    target: Double?,
    diameter: Dp = 128.dp,
    strokeWidth: Dp = 11.dp,
) {
    val done = MaterialTheme.colorScheme.primary
    val success = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ratio = if (target != null && target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
    val withPending =
        if (target != null && target > 0) ((value + pending) / target).toFloat().coerceIn(0f, 1f) else 0f
    val reached = target != null && value >= target

    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val origin = Offset(stroke / 2, stroke / 2)
            fun arc(color: androidx.compose.ui.graphics.Color, sweep: Float) {
                if (sweep <= 0f) return
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = origin, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            arc(track, 360f)
            if (withPending > ratio) arc(done.copy(alpha = 0.28f), 360f * withPending)
            arc(if (reached) success else done, 360f * ratio)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                CurriculumEngine.fmt(value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (reached) success else MaterialTheme.colorScheme.primary,
            )
            Text(
                target?.let { "/ ${CurriculumEngine.fmt(it)}" } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
