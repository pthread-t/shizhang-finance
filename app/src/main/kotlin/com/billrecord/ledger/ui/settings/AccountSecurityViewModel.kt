package com.billrecord.ledger.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.sync.AuthApi
import com.billrecord.ledger.sync.SyncRepository
import com.billrecord.ledger.sync.SyncStatus
import com.billrecord.shared.AccountProfileDto
import com.billrecord.shared.DeviceDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountSecurityState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val profile: AccountProfileDto? = null,
    val devices: List<DeviceDto> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val confirmUnsafeLogout: Boolean = false,
    val signedOut: Boolean = false,
)

@HiltViewModel
class AccountSecurityViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountSecurityState())
    val state: StateFlow<AccountSecurityState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { authApi.profile() to authApi.devices() }
            .onSuccess { (profile, devices) -> _state.value = AccountSecurityState(loading = false, profile = profile, devices = devices) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "无法加载账户信息") }
    }

    fun updateCredentials(currentPassword: String, newUsername: String, newPassword: String, confirmation: String) {
        if (currentPassword.isBlank()) { _state.value = _state.value.copy(error = "请输入当前密码"); return }
        if (newPassword.isNotEmpty() && newPassword != confirmation) { _state.value = _state.value.copy(error = "两次输入的新密码不一致"); return }
        if (newUsername.isBlank() && newPassword.isBlank()) { _state.value = _state.value.copy(error = "请填写新用户名或新密码"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            runCatching { authApi.updateCredentials(currentPassword, newUsername.trim().ifBlank { null }, newPassword.ifBlank { null }) }
                .onSuccess { response ->
                    _state.value = _state.value.copy(
                        busy = false,
                        profile = response.profile,
                        message = "账户信息已更新，其他设备已退出登录",
                    )
                    load()
                }
                .onFailure { _state.value = _state.value.copy(busy = false, error = it.message ?: "账户信息更新失败") }
        }
    }

    fun revokeDevice(id: String) = viewModelScope.launch {
        runCatching { authApi.revokeDevice(id); authApi.devices() }
            .onSuccess { _state.value = _state.value.copy(devices = it, message = "设备会话已撤销") }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "撤销设备失败") }
    }

    fun requestSignOut() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        val synchronized = syncRepository.synchronize()
        if (synchronized || !authApi.signedIn) completeSignOut()
        else _state.value = _state.value.copy(busy = false, confirmUnsafeLogout = true)
    }

    fun cancelUnsafeLogout() { _state.value = _state.value.copy(confirmUnsafeLogout = false) }
    fun forceSignOut() = completeSignOut()
    fun clearMessage() { _state.value = _state.value.copy(message = null, error = null) }

    private fun completeSignOut() {
        authApi.signOut()
        syncRepository.status.value = SyncStatus.LOCAL_ONLY
        _state.value = _state.value.copy(busy = false, confirmUnsafeLogout = false, signedOut = true)
    }
}
