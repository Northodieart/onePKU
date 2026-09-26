package me.petertian.onepku.ui.curriculum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var settle: Job? = null

    init { reload() }

    /** silent = 保留上一次结果,不闪 loading。 */
    fun reload(silent: Boolean = false) {
        val planId = profiles.current().planId
        if (planId.isNullOrEmpty()) {
            _ui.update { it.copy(hasProfile = false, content = UiData.Loading, planTitle = "") }
            return
        }
        if (!silent) _ui.update { it.copy(hasProfile = true, content = UiData.Loading) }
        viewModelScope.launch {
            // index.json 有 700 多 KB,读盘与方案解析都放 IO 线程。
            val title = withContext(Dispatchers.IO) { repo.plans.entry(planId)?.title.orEmpty() }
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
        reload(silent = true)
    }

    /** 在修课程的学分:教学网给不出,只能填。边打字边重算太费网络,停一下再算。 */
    fun setCredit(courseName: String, credits: Double?) {
        profiles.setManualCredit(courseName, credits)
        settle?.cancel()
        settle = viewModelScope.launch {
            delay(600)
            reload(silent = true)
        }
    }

    fun creditOf(courseName: String): Double? =
        profiles.current().manualCredits[CurriculumEngine.normalizeCourseName(courseName)]

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

    // 重新计算完成度时保持当前页,归入一门课后不会跳回第一页。
    var lastPage by rememberSaveable { mutableIntStateOf(0) }

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
            is UiData.Failure -> ErrorBox(data.message, onRetry = { vm.reload() })
            is UiData.Ready -> ProgressPager(
                progress = data.value.second,
                padding = padding,
                vm = vm,
                startPage = lastPage,
                onStartPageChange = { lastPage = it },
            )
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
private fun ProgressPager(
    progress: Progress,
    padding: PaddingValues,
    vm: CurriculumViewModel,
    startPage: Int,
    onStartPageChange: (Int) -> Unit,
) {
    // 第一页毕业总学分,之后每个大类一页,最后按需确认页收尾。
    val titles = buildList {
        add("毕业总学分")
        progress.sections.forEach { add(it.name) }
        if (progress.pending.isNotEmpty() || progress.ignored.isNotEmpty() || progress.hasInProgress) add("待确认")
    }
    // 归入一门课后会重新计算完成度,页面不能跳回第一页,所以页码记在调用方。
    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, maxOf(0, titles.size - 1)),
        pageCount = { titles.size },
    )
    LaunchedEffect(pagerState.currentPage) { onStartPageChange(pagerState.currentPage) }

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
                page <= progress.sections.size -> SectionPage(progress.sections[page - 1])
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
        item {
            RingHeader(
                value = progress.earned,
                pending = progress.inProgress,
                target = progress.required,
                unit = "学分",
                requirement = progress.plan.totalCredits?.let {
                    if (it.max > it.min) "${fmt(it.min)}～${fmt(it.max)} 学分" else "${fmt(it.min)} 学分"
                },
                gap = progress.required?.let { maxOf(0.0, it - progress.earned - progress.inProgress) },
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
                    progress.sections.forEach { SectionBar(it) }
                }
            }
        }
        courseLists(counted(progress.sections), inProgressOf(progress.sections), showOwner = true)
    }
}

@Composable
private fun SectionPage(section: Section) {
    val value = valueOf(section)
    val pending = pendingOf(section)
    val gap = if (section.unit == "学时") null else section.min?.let { maxOf(0.0, it - value - pending) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            RingHeader(
                value = value,
                pending = pending,
                target = section.min,
                unit = section.unit ?: "学分",
                requirement = section.requirement,
                gap = gap,
            )
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
                        section.children.forEach { SectionBar(it) }
                    }
                }
            }
        }
        courseLists(counted(listOf(section)), inProgressOf(listOf(section)), showOwner = section.children.isNotEmpty())
    }
}

/** 圆环居中,数值、缺口与要求依次写在下方;大类页与子系列页共用同一套排版。 */
@Composable
private fun RingHeader(
    value: Double,
    pending: Double,
    target: Double?,
    unit: String,
    requirement: String?,
    gap: Double?,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CurriculumRing(value, pending, target)
        Text(
            buildString {
                append("已修 ${fmt(value)}")
                if (pending > 0) append(" + 在修 ${fmt(pending)}")
                target?.let { append(" / ${fmt(it)} $unit") } ?: append(" $unit")
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        gap?.let {
            Text(
                if (it <= 0.0) "已满足" else "还差 ${fmt(it)} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = if (it <= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        requirement?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun fmt(value: Double?): String = CurriculumEngine.fmt(value)

@Composable
private fun PendingPage(progress: Progress, vm: CurriculumViewModel) {
    var showIgnored by remember { mutableStateOf(false) }
    // 在修的课程不管有没有归上类,都在这页填学分——教学网不给学分,方案里那些行也大多没解析出来。
    val doing = inProgressOf(progress.sections) +
        progress.pending.filter { it.status == CurriculumEngine.CourseStatus.IN_PROGRESS }.map { it to "未归类" }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (doing.isNotEmpty()) {
            item {
                Text(
                    "在修课程学分",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "教学网的课程列表里没有学分这一项,填过的课才会算进圆环和缺口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(doing, key = { "credit-${it.first.key}" }) { (course, owner) ->
                CreditRow(course, owner, vm)
            }
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
private fun SectionBar(section: Section) {
    val value = valueOf(section)
    val pending = pendingOf(section)
    val target = section.min
    val unit = section.unit ?: "学分"
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(section.name, style = MaterialTheme.typography.bodySmall)
            Text(
                buildString {
                    append(fmt(value))
                    if (pending > 0) append(" + ${fmt(pending)}")
                    target?.let { append(" / ${fmt(it)}") }
                    append(" $unit")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { ((value + pending) / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
    }
}

/** 已修与在修两份列表;父级页面把子系列的课一并列出,并标注归属。 */
private fun LazyListScope.courseLists(
    courses: List<Pair<MatchedCourse, String>>,
    doing: List<Pair<MatchedCourse, String>>,
    showOwner: Boolean,
) {
    if (courses.isEmpty() && doing.isEmpty()) {
        item {
            Text(
                "这一类还没有计入的课程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (courses.isNotEmpty()) {
        item { Text("已修课程 ${courses.size} 门", style = MaterialTheme.typography.titleSmall) }
        items(courses, key = { it.first.key }) { CourseRow(it.first, if (showOwner) it.second else null) }
    }
    if (doing.isNotEmpty()) {
        val missing = doing.count { it.first.credits == null }
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("在修课程 ${doing.size} 门", style = MaterialTheme.typography.titleSmall)
                if (missing > 0) {
                    Text(
                        "其中 $missing 门还没填学分,未计入圆环;在末页逐门填写",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        items(doing, key = { "doing-${it.first.key}" }) { CourseRow(it.first, if (showOwner) it.second else null) }
    }
}

/** 系列及其子系列下已计入的课程,附带所属系列名。 */
private fun counted(sections: List<Section>): List<Pair<MatchedCourse, String>> =
    sections.flatMap { s -> s.courses.map { it to s.name } + counted(s.children) }

private fun inProgressOf(sections: List<Section>): List<Pair<MatchedCourse, String>> =
    sections.flatMap { s -> s.inProgressCourses.map { it to s.name } + inProgressOf(s.children) }

@Composable
private fun CourseRow(course: MatchedCourse, owner: String? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    listOfNotNull(owner, course.term, course.score.ifBlank { null })
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                statusLabel(course),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 在修课程的学分输入框;填完即写入本机并重算,不用等保存按钮。 */
@Composable
private fun CreditRow(course: MatchedCourse, owner: String, vm: CurriculumViewModel) {
    var text by remember(course.key) { mutableStateOf(course.credits?.let { fmt(it) } ?: "") }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    listOfNotNull(owner, course.term).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val v = raw.filter { it.isDigit() || it == '.' }.take(5)
                    val parsed = v.toDoubleOrNull()
                    text = v
                    // "3." 这种半截不算改;清空表示撤销。
                    if (v.isEmpty() || parsed != null) vm.setCredit(course.name, parsed)
                },
                singleLine = true,
                suffix = { Text("学分") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(112.dp),
            )
        }
    }
}

/** 右侧优先显示拿到多少学分,拿不到才退回状态词。 */
private fun statusLabel(course: MatchedCourse): String {
    val credits = course.credits
    if (credits != null && course.status != CurriculumEngine.CourseStatus.FAILED) return "${fmt(credits)} 学分"
    return when (course.status) {
        CurriculumEngine.CourseStatus.IN_PROGRESS -> "在修·待填学分"
        CurriculumEngine.CourseStatus.PASSED -> "已获"
        CurriculumEngine.CourseStatus.FAILED -> "未通过"
        CurriculumEngine.CourseStatus.WITHDRAWN -> "退课"
        CurriculumEngine.CourseStatus.OTHER -> "其他"
    }
}

/** 单位决定度量:按门数的系列看点数,其余看学分。 */
private fun valueOf(section: Section): Double =
    if (section.unit == "门") section.passedCount.toDouble() else section.earned

/** 按门计数的系列不画在修弧——门数和学分不是一个量纲。 */
private fun pendingOf(section: Section): Double =
    if (section.unit == "门") 0.0 else section.inProgress

/** 圆环:已修为实色弧,在修按桌面端配色画成淡色弧延伸,达标换成功色。 */
@Composable
private fun CurriculumRing(value: Double, pending: Double, target: Double?) {
    val done = MaterialTheme.colorScheme.primary
    val success = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ratio = if (target != null && target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
    val withPending = if (target != null && target > 0) ((value + pending) / target).toFloat().coerceIn(0f, 1f) else 0f
    val reached = target != null && value + pending >= target

    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(128.dp)) {
            val stroke = 11.dp.toPx()
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
                fmt(value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (reached) success else MaterialTheme.colorScheme.primary,
            )
            target?.let {
                Text(
                    "/ ${fmt(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
