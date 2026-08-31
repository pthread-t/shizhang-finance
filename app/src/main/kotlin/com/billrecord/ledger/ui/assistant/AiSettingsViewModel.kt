package com.billrecord.ledger.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.ai.AiCompletionClient
import com.billrecord.ledger.ai.AiDataRepository
import com.billrecord.ledger.ai.AiProviderKind
import com.billrecord.ledger.data.local.AiProviderProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repository: AiDataRepository,
    private val client: AiCompletionClient,
) : ViewModel() {
    val profiles = repository.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun save(id: String?, name: String, kind: AiProviderKind, baseUrl: String, model: String, apiKey: String) = viewModelScope.launch {
        runBusy {
            repository.saveProfile(id, name, kind, baseUrl, model, apiKey.takeIf(String::isNotBlank))
            message.value = "模型配置已保存"
        }
    }

    fun makeDefault(id: String) = viewModelScope.launch { repository.setDefaultProfile(id) }

    fun delete(id: String) = viewModelScope.launch {
        repository.deleteProfile(id)
        message.value = "模型配置和本机密钥已删除"
    }

    fun test(profile: AiProviderProfileEntity) = viewModelScope.launch {
        runBusy {
            val key = repository.apiKey(profile.id) ?: error("请先为该配置填写 API Key")
            client.test(profile, key)
            message.value = "连接成功"
        }
    }

    fun clearHistory() = viewModelScope.launch {
        repository.clearHistory()
        message.value = "本机 AI 会话已清空"
    }

    fun clearMessage() { message.value = null }
    fun hasApiKey(profileId: String): Boolean = repository.apiKey(profileId) != null

    private suspend fun runBusy(block: suspend () -> Unit) {
        busy.value = true
        runCatching { block() }.onFailure { message.value = it.message ?: "操作失败" }
        busy.value = false
    }
}
