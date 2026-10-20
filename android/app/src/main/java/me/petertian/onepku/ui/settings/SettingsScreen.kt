package me.petertian.onepku.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import me.petertian.onepku.BuildConfig
import me.petertian.onepku.core.session.Service
import me.petertian.onepku.data.auth.AuthManager
import me.petertian.onepku.data.news.SchoolNoticeApi
import me.petertian.onepku.data.repo.DepartmentStore
import me.petertian.onepku.data.update.AndroidRelease
import me.petertian.onepku.data.update.UpdateApi
import me.petertian.onepku.data.update.UpdateCheck
import me.petertian.onepku.ui.navigation.back
import me.petertian.onepku.ui.navigation.toLogin
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val username: String? = null,
    val services: Map<Service, Boolean> = emptyMap(),
    val department: String? = null,
    val schools: List<String> = emptyList(),
    val pickingSchool: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val update: UpdateCheck? = null,
    val checking: Boolean = false,
    val download: Pair<Long, Long?>? = null,
    val installFile: String? = null,
    val needPermission: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthManager,
    private val departments: DepartmentStore,
    schoolNotices: SchoolNoticeApi,
    private val updates: UpdateApi,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState(schools = schoolNotices.schools()))
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        _ui.update {
            it.copy(
                username = auth.storedUsername(),
                services = Service.entries.associateWith { s -> auth.isLoggedIn(s) },
                department = departments.current(),
            )
        }
    }

    fun setPickingSchool(picking: Boolean) = _ui.update { it.copy(pickingSchool = picking) }

    fun chooseSchool(school: String?) {
        departments.select(school)
        _ui.update { it.copy(pickingSchool = false, department = departments.current()) }
    }

    fun reconnect(service: Service) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                auth.relogin(service)
                _ui.update { it.copy(message = "${service.displayName}已重新连接") }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "${service.displayName}重连失败:${e.message}") }
            }
            reload()
            _ui.update { it.copy(busy = false) }
        }
    }

    fun disconnect(service: Service) {
        auth.logout(service)
        reload()
    }

    fun logoutAll(onDone: () -> Unit) {
        auth.logoutAll()
        onDone()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    checking = true,
                    message = null,
                    update = null,
                    download = null,
                    installFile = null,
                    needPermission = false,
                )
            }
            try {
                _ui.update { it.copy(update = updates.check()) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "检查更新失败:${e.message}") }
            }
            _ui.update { it.copy(checking = false) }
        }
    }

    fun downloadUpdate(release: AndroidRelease) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    message = null,
                    download = 0L to release.size.takeIf { size -> size > 0 },
                )
            }
            try {
                val file = updates.download(release) { done, total ->
                    _ui.update { state -> state.copy(download = done to (total ?: state.download?.second)) }
                }
                _ui.update { it.copy(download = null, installFile = file.path) }
                installDownloaded()
            } catch (e: Exception) {
                _ui.update { it.copy(message = "更新未能安装:${e.message}", download = null) }
            }
        }
    }

    /** 装不了就留在原地：要么没授权，要么系统没给可用的安装界面，两种都说清下一步。 */
    fun installDownloaded() {
        val path = _ui.value.installFile ?: return
        if (!updates.canInstall()) {
            _ui.update { it.copy(needPermission = true, message = "需要你先允许 OnePKU 安装应用") }
            return
        }
        if (!updates.launchInstall(File(path))) {
            _ui.update { it.copy(needPermission = false, message = "没能打开系统安装界面,请从下载记录里手动打开") }
        }
    }

    fun openInstallPermission() {
        if (!updates.openInstallPermissionSettings()) {
            _ui.update { it.copy(message = "找不到系统的应用权限设置页") }
            return
        }
        _ui.update { it.copy(needPermission = false, message = "允许后回到这里,点「继续安装」") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的凭证与全部服务会话") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logoutAll { nav.toLogin() }
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }

    if (ui.pickingSchool) {
        AlertDialog(
            onDismissRequest = { vm.setPickingSchool(false) },
            title = { Text("选择院系") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(ui.schools) { school ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.chooseSchool(school) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(school, style = MaterialTheme.typography.bodyMedium)
                            if (school == ui.department) {
                                Text("当前", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.chooseSchool(null) }) { Text("清除手动选择") }
            },
            dismissButton = {
                TextButton(onClick = { vm.setPickingSchool(false) }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "服务连接",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Service.entries.forEach { service ->
                item(key = service.key) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(service.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (ui.services[service] == true) "已连接" else "未连接",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ui.services[service] == true) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.reconnect(service) }, enabled = !ui.busy) {
                                Text("重连")
                            }
                            TextButton(onClick = { vm.disconnect(service) }, enabled = !ui.busy) {
                                Text("断开")
                            }
                        }
                    }
                }
            }

            item(key = "department") {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("本院通知", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                ui.department?.takeIf { it.isNotBlank() }
                                    ?: "未能识别院系,可手动选择",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.setPickingSchool(true) }) { Text("选择院系") }
                    }
                }
            }

            if (ui.message != null) {
                item {
                    Text(
                        ui.message!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }

            item {
                Text(
                    "账号",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ui.username ?: "未登录", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "凭证加密存储于本机",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { confirmLogout = true }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                Text(
                    "关于",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("OnePKU Android", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME}\n数据仅保存在本机;学校数据归北京大学及各原站所有",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val progress = ui.download
                        val available = (ui.update as? UpdateCheck.Available)?.release
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { vm.checkForUpdates() },
                                enabled = !ui.checking && progress == null,
                            ) { Text(if (ui.checking) "检查中…" else "检查更新") }
                            if (available != null && progress == null && ui.installFile == null) {
                                TextButton(onClick = { vm.downloadUpdate(available) }) {
                                    Text("下载 v${available.version}")
                                }
                            }
                            if (ui.installFile != null) {
                                TextButton(onClick = { vm.installDownloaded() }) { Text("继续安装") }
                            }
                            if (ui.needPermission) {
                                TextButton(onClick = { vm.openInstallPermission() }) { Text("去允许") }
                            }
                        }
                        if (progress != null) {
                            val total = progress.second
                            UpdateStatus(
                                if (total == null) "下载中 ${mbOf(progress.first)}"
                                else "下载中 ${mbOf(progress.first)} / ${mbOf(total)}",
                            )
                        }
                        when (val state = ui.update) {
                            is UpdateCheck.UpToDate -> UpdateStatus("已是最新版本")
                            is UpdateCheck.NoAndroidPackage -> UpdateStatus("这个版本还没有安卓安装包")
                            is UpdateCheck.Available -> UpdateStatus(
                                "发现新版本 v${state.release.version},约 ${mbOf(state.release.size)}",
                            )
                            null -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateStatus(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun mbOf(bytes: Long): String = "${bytes / 1024 / 1024} MB"
