package com.billrecord.ledger.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.backup.BackupManager
import com.billrecord.ledger.data.backup.RestorePreview
import com.billrecord.ledger.data.export.ExportFormat
import com.billrecord.ledger.data.export.ExportManager
import com.billrecord.ledger.data.importer.BillImportService
import com.billrecord.ledger.data.importer.ImportPreview
import com.billrecord.ledger.data.local.SyncConflictEntity
import com.billrecord.ledger.sync.AuthApi
import com.billrecord.ledger.sync.SyncRepository
import com.billrecord.ledger.sync.SyncScheduler
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.shared.BookRole
import com.billrecord.shared.MemberDto
import com.billrecord.shared.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import javax.inject.Inject

data class SettingsUiState(
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val inviteCode: String? = null,
    val recoveryCode: String? = null,
    val importPreview: ImportPreview? = null,
    val members: List<MemberDto> = emptyList(),
    val currentRole: BookRole? = null,
    val restorePreview: PendingRestorePreview? = null,
)

data class PendingRestorePreview(val uri: Uri, val replace: Boolean, val preview: RestorePreview)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val authApi: AuthApi,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
    private val exportManager: ExportManager,
    private val backupManager: BackupManager,
    private val importService: BillImportService,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(signedIn = authApi.signedIn))
    val state: StateFlow<SettingsUiState> = _state
    val serverUrl = preferences.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val automationEnabled = preferences.automationEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val secureScreen = preferences.secureScreenEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val appLock = preferences.appLockEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val dailyReminder = preferences.dailyReminderEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val themeAccent = preferences.themeAccent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "JADE")
    val syncStatus: StateFlow<SyncStatus> = syncRepository.status
    val conflicts = syncRepository.conflicts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveServerUrl(value: String) = launchTask("服务器地址已保存") { authApi.setServerUrl(value) }

    fun login(username: String, password: String, bootstrap: Boolean, inviteCode: String?, recoveryCode: String?) {
        launchTask("已连接云同步") {
            val response = if (!recoveryCode.isNullOrBlank()) authApi.recover(username, recoveryCode, password)
            else authApi.login(username, password, bootstrap, inviteCode)
            _state.value = _state.value.copy(signedIn = true, recoveryCode = response.recoveryCode)
            syncScheduler.syncNow()
        }
    }

    fun syncNow() = syncScheduler.syncNow()

    fun resolveConflict(conflict: SyncConflictEntity, localFields: Set<String>) = launchTask("同步冲突已处理") {
        syncRepository.resolveConflict(conflict, localFields)
        syncScheduler.syncNow()
    }

    fun createInvite() = viewModelScope.launch {
        runBusy {
            val bookId = preferences.selectedBookId.first() ?: error("未选择账本")
            val invite = authApi.createInvite(bookId, BookRole.EDITOR)
            _state.value = _state.value.copy(inviteCode = invite.code, message = "邀请码已生成")
        }
    }

    fun loadCloudManagement() = viewModelScope.launch {
        if (!state.value.signedIn) return@launch
        runBusy {
            val bookId = preferences.selectedBookId.first() ?: error("未选择账本")
            val members = authApi.members(bookId)
            _state.value = _state.value.copy(
                members = members,
                currentRole = members.firstOrNull { it.userId == preferences.userId() }?.role,
            )
        }
    }

    fun toggleMemberRole(member: MemberDto) = launchTask("成员角色已更新") {
        val bookId = preferences.selectedBookId.first() ?: error("未选择账本")
        val role = if (member.role == BookRole.EDITOR) BookRole.VIEWER else BookRole.EDITOR
        authApi.updateMemberRole(bookId, member.userId, role)
        _state.value = _state.value.copy(members = authApi.members(bookId))
    }

    fun removeMember(member: MemberDto) = launchTask("成员已移除") {
        val bookId = preferences.selectedBookId.first() ?: error("未选择账本")
        authApi.removeMember(bookId, member.userId)
        _state.value = _state.value.copy(members = authApi.members(bookId))
    }

    fun acceptInvite(code: String) = launchTask("已加入家庭账本") { authApi.acceptInvite(code); syncScheduler.syncNow() }

    fun export(uri: Uri, format: ExportFormat) = launchTask("导出完成") {
        exportManager.exportToUri(context, uri, TransactionFilter(), format)
    }

    fun previewImport(uri: Uri) = viewModelScope.launch {
        runBusy { _state.value = _state.value.copy(importPreview = importService.preview(uri)) }
    }

    fun confirmImport() = launchTask("账单导入完成") {
        val preview = requireNotNull(_state.value.importPreview)
        val count = importService.import(preview)
        _state.value = _state.value.copy(importPreview = null, message = "已导入 $count 笔账单")
    }

    fun dismissImport() { _state.value = _state.value.copy(importPreview = null) }

    fun createBackup(uri: Uri, password: String) = launchTask("加密备份已创建") {
        context.contentResolver.openOutputStream(uri, "w")!!.use { backupManager.create(password.toCharArray(), it) }
    }

    fun previewBackup(uri: Uri, password: String, replace: Boolean) = launchTask("备份校验完成") {
        val preview = context.contentResolver.openInputStream(uri)!!.use { backupManager.preview(password.toCharArray(), it) }
        _state.value = _state.value.copy(restorePreview = PendingRestorePreview(uri, replace, preview))
    }

    fun restoreBackup(uri: Uri, password: String, replace: Boolean) = launchTask("备份恢复完成") {
        context.contentResolver.openInputStream(uri)!!.use { backupManager.restore(password.toCharArray(), it, replace) }
        _state.value = _state.value.copy(restorePreview = null)
    }
    fun dismissRestorePreview() { _state.value = _state.value.copy(restorePreview = null) }

    fun setAutomationEnabled(value: Boolean) = viewModelScope.launch { preferences.setAutomationEnabled(value) }
    fun setSecureScreen(value: Boolean) = viewModelScope.launch { preferences.setSecureScreen(value) }
    fun setAppLock(value: Boolean) = viewModelScope.launch { preferences.setAppLock(value) }
    fun setDailyReminder(value: Boolean) = viewModelScope.launch { preferences.setDailyReminder(value) }
    fun cycleThemeAccent() = viewModelScope.launch {
        val accents = listOf("JADE", "RUST", "BRASS", "SLATE")
        preferences.setThemeAccent(accents[(accents.indexOf(themeAccent.value) + 1).mod(accents.size)])
    }
    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }
    fun acknowledgeRecoveryCode() { _state.value = _state.value.copy(recoveryCode = null) }

    private fun launchTask(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runBusy { block(); _state.value = _state.value.copy(message = success) }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        runCatching { block() }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "操作失败") }
        _state.value = _state.value.copy(busy = false)
    }
}
