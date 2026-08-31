package com.billrecord.ledger.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.ai.AiChartFactory
import com.billrecord.ledger.ai.AiCompletionClient
import com.billrecord.ledger.ai.AiDataRepository
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AiMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiAssistantViewModel @Inject constructor(
    private val aiRepository: AiDataRepository,
    private val ledgerRepository: LedgerRepository,
    private val client: AiCompletionClient,
    private val json: Json,
) : ViewModel() {
    val readyProfiles = aiRepository.observeProfiles().map { values -> values.filter { aiRepository.apiKey(it.id) != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedBookId = ledgerRepository.observeSelectedBookId().filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val selectedConversationId = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    val conversations = selectedBookId.flatMapLatest { bookId ->
        if (bookId.isBlank()) flowOf(emptyList()) else aiRepository.observeConversations(bookId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages = selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else aiRepository.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectConversation(id: String) { selectedConversationId.value = id }

    fun newConversation() = viewModelScope.launch {
        if (selectedBookId.value.isBlank()) return@launch
        selectedConversationId.value = aiRepository.newConversation(selectedBookId.value, "新对话")
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        aiRepository.deleteConversation(id)
        if (selectedConversationId.value == id) selectedConversationId.value = null
    }

    fun send(question: String) = viewModelScope.launch {
        if (question.isBlank() || busy.value) return@launch
        val profile = aiRepository.defaultProfile() ?: return@launch
        val key = aiRepository.apiKey(profile.id) ?: return@launch
        val conversationId = selectedConversationId.value ?: aiRepository.newConversation(selectedBookId.value, question).also { selectedConversationId.value = it }
        val now = System.currentTimeMillis()
        aiRepository.saveMessage(AiMessageEntity(UUID.randomUUID().toString(), conversationId, "USER", question.trim(), "COMPLETE", createdAt = now))
        val answerId = UUID.randomUUID().toString()
        aiRepository.saveMessage(
            AiMessageEntity(answerId, conversationId, "ASSISTANT", "", "LOADING", profile.id, profile.model, createdAt = now + 1),
        )
        busy.value = true
        runCatching {
            val catalog = aiRepository.catalog(selectedBookId.value)
            val plan = aiRepository.validatePlan(client.plan(question, catalog, profile, key), catalog)
            val result = aiRepository.execute(plan, catalog)
            val chart = AiChartFactory.create(result)
            if (result.transactionCount == 0) {
                aiRepository.saveMessage(
                    AiMessageEntity(answerId, conversationId, "ASSISTANT", "当前条件下没有账单记录，请调整时间或筛选条件。", "COMPLETE", profile.id, profile.model, json.encodeToString(plan), json.encodeToString(result), json.encodeToString(chart), createdAt = now + 1),
                )
            } else {
                val text = StringBuilder()
                client.analyze(plan, result, profile, key).collect { text.append(it.text) }
                aiRepository.saveMessage(
                    AiMessageEntity(answerId, conversationId, "ASSISTANT", text.toString(), "COMPLETE", profile.id, profile.model, json.encodeToString(plan), json.encodeToString(result), json.encodeToString(chart), createdAt = now + 1),
                )
            }
        }.onFailure { error ->
            aiRepository.saveMessage(
                AiMessageEntity(answerId, conversationId, "ASSISTANT", "", "ERROR", profile.id, profile.model, errorMessage = error.message ?: "生成失败", createdAt = now + 1),
            )
        }
        busy.value = false
    }

    fun retry(message: AiMessageEntity) {
        val values = messages.value
        val index = values.indexOfFirst { it.id == message.id }
        values.subList(0, index.coerceAtLeast(0)).lastOrNull { it.role == "USER" }?.let { send(it.text) }
    }
}
