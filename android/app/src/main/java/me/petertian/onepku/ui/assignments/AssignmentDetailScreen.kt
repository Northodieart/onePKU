package me.petertian.onepku.ui.assignments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.petertian.onepku.data.course.AssignmentDetail
import me.petertian.onepku.data.course.FeedbackAttempt
import me.petertian.onepku.data.repo.CourseRepository
import me.petertian.onepku.ui.components.ErrorBox
import me.petertian.onepku.ui.components.LoadingBox
import me.petertian.onepku.ui.components.UiData
import me.petertian.onepku.ui.navigation.back
import me.petertian.onepku.ui.today.deadlineLabel
import javax.inject.Inject

data class AssignmentDetailUiState(
    val title: String = "",
    val detail: UiData<AssignmentDetail> = UiData.Loading,
    val attempts: UiData<List<FeedbackAttempt>> = UiData.Loading,
)

@HiltViewModel
class AssignmentDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CourseRepository,
) : ViewModel() {
    private val courseId: String = checkNotNull(savedState["courseId"])
    private val contentId: String = checkNotNull(savedState["contentId"])
    private val title: String = savedState.get<String>("title").orEmpty()

    private val _ui = MutableStateFlow(AssignmentDetailUiState(title = title))
    val ui: StateFlow<AssignmentDetailUiState> = _ui.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            coroutineScope {
                val detailJob = async {
                    try {
                        UiData.Ready(repo.assignmentDetail(courseId, contentId))
                    } catch (e: Exception) {
                        UiData.Failure(e.message ?: "作业详情加载失败")
                    }
                }
                val attemptsJob = async {
                    try {
                        UiData.Ready(repo.attempts(courseId, contentId))
                    } catch (e: Exception) {
                        UiData.Failure(e.message ?: "提交记录加载失败")
                    }
                }
                _ui.update { it.copy(detail = detailJob.await(), attempts = attemptsJob.await()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentDetailScreen(nav: NavHostController, vm: AssignmentDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作业详情", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                when (val d = ui.detail) {
                    is UiData.Loading -> Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { Text("加载中…", style = MaterialTheme.typography.bodySmall) }
                    is UiData.Failure -> Text(
                        d.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is UiData.Ready -> DetailCard(d.value, ui.title)
                }
            }

            item {
                Text("提交记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            when (val attempts = ui.attempts) {
                is UiData.Loading -> item {
                    Text("提交记录加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is UiData.Failure -> item {
                    Text(attempts.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                is UiData.Ready -> {
                    if (attempts.value.isEmpty()) {
                        item {
                            Text(
                                "暂无提交记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(attempts.value, key = { it.id }) { attempt -> AttemptCard(attempt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(detail: AssignmentDetail, fallbackTitle: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                detail.title.ifEmpty { fallbackTitle },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "截止:${detail.deadlineRaw ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    deadlineLabel(detail.deadlineEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "状态:${detail.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.instructions.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail.instructions, style = MaterialTheme.typography.bodyMedium)
            }
            if (detail.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("附件", style = MaterialTheme.typography.labelLarge)
                detail.attachments.forEach { a ->
                    Text("· ${a.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AttemptCard(attempt: FeedbackAttempt) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(attempt.label.ifEmpty { "提交" }, style = MaterialTheme.typography.titleSmall)
                if (attempt.score != null) {
                    Text(
                        attempt.score + (attempt.pointsPossible?.let { " / $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text("未评分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!attempt.feedback.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("评语:${attempt.feedback}", style = MaterialTheme.typography.bodySmall)
            }
            if (attempt.files.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                attempt.files.forEach { f ->
                    Text("· ${f.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
