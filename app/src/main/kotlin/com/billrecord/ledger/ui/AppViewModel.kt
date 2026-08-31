package com.billrecord.ledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.BuildConfig
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.sync.AuthApi
import com.billrecord.ledger.sync.SyncRepository
import com.billrecord.ledger.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppLaunchState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val loginRequired: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val preferences: AppPreferences,
    private val authApi: AuthApi,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    private val _launchState = MutableStateFlow(AppLaunchState())
    val launchState = _launchState.asStateFlow()
    val canEdit = repository.observeCanEdit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val syncStatus = syncRepository.status
    private var initialized = false

    init {
        if (BuildConfig.CLOUD_FIRST) {
            viewModelScope.launch {
                preferences.sessionUserId.collect { userId ->
                    if (initialized && userId == null) {
                        _launchState.value = AppLaunchState(loginRequired = true)
                    }
                }
            }
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            if (!BuildConfig.CLOUD_FIRST) {
                repository.initialize()
                _launchState.value = AppLaunchState(ready = true)
            } else if (authApi.signedIn) {
                initializeCloud()
            } else {
                _launchState.value = AppLaunchState(loginRequired = true)
            }
        }
    }

    fun login(username: String, password: String) {
        if (_launchState.value.busy) return
        viewModelScope.launch {
            _launchState.value = AppLaunchState(busy = true, loginRequired = true)
            runCatching {
                val response = authApi.login(username.trim(), password, bootstrap = false)
                if (!preferences.bindCloudUser(response.userId)) {
                    authApi.signOut()
                    error("此安装已绑定其他体验账号；请清除 App 数据后再切换账号")
                }
                synchronizeCloudOrThrow()
            }.onFailure { error ->
                _launchState.value = AppLaunchState(loginRequired = !authApi.signedIn, error = error.message ?: "登录或首次同步失败")
            }
        }
    }

    fun retryCloudSync() {
        if (_launchState.value.busy || !authApi.signedIn) return
        viewModelScope.launch { synchronizeCloud() }
    }

    fun retryBackgroundSync() {
        if (!authApi.signedIn) return
        if (_launchState.value.ready) syncScheduler.syncNow() else retryCloudSync()
    }

    private suspend fun initializeCloud() {
        val userId = preferences.userId()
        if (userId == null) {
            _launchState.value = AppLaunchState(loginRequired = true)
            return
        }
        if (!preferences.bindCloudUser(userId)) {
            authApi.signOut()
            _launchState.value = AppLaunchState(loginRequired = true, error = "此安装已绑定其他体验账号；请清除 App 数据后再切换账号")
            return
        }
        val books = repository.getBooks()
        if (requiresBlockingInitialSync(signedIn = true, cachedBookCount = books.size)) {
            synchronizeCloud()
            return
        }
        val selectedId = preferences.selectedBookId.first()
        val selected = books.firstOrNull { it.id == selectedId }
            ?: books.firstOrNull { it.name == "家庭体验账本" }
            ?: books.first()
        repository.selectBook(selected.id)
        _launchState.value = AppLaunchState(ready = true)
        syncScheduler.syncNow()
    }

    private suspend fun synchronizeCloud() {
        _launchState.value = AppLaunchState(busy = true)
        runCatching { synchronizeCloudOrThrow() }.onFailure { error ->
            _launchState.value = AppLaunchState(loginRequired = !authApi.signedIn, error = error.message ?: "无法连接预发布云端")
        }
    }

    private suspend fun synchronizeCloudOrThrow() {
        val userId = requireNotNull(preferences.userId()) { "登录会话不存在" }
        check(preferences.bindCloudUser(userId)) { "此安装已绑定其他体验账号；请清除 App 数据后再切换账号" }
        check(syncRepository.synchronize()) { "云端同步失败，请检查网络后重试" }
        val books = repository.getBooks()
        check(books.isNotEmpty()) { "云端账号尚未分配体验账本" }
        val selected = books.firstOrNull { it.name == "家庭体验账本" } ?: books.first()
        repository.selectBook(selected.id)
        _launchState.value = AppLaunchState(ready = true)
    }
}
