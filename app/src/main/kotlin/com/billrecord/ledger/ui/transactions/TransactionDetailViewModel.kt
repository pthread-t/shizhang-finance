package com.billrecord.ledger.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.EditTransactionInput
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AttachmentEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.data.local.TransactionSplitEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TransactionDetailState(
    val loading: Boolean = true,
    val transaction: TransactionEntity? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val tagNames: List<String> = emptyList(),
    val splits: List<TransactionSplitEntity> = emptyList(),
    val merchantName: String? = null,
    val projectName: String? = null,
    val memberName: String? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val message: String? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LedgerRepository,
) : ViewModel() {
    private val transactionId: String = requireNotNull(savedStateHandle["transactionId"])
    private val _state = MutableStateFlow(TransactionDetailState())
    val state: StateFlow<TransactionDetailState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        val current = repository.getTransaction(transactionId)
        if (current == null) {
            _state.value = _state.value.copy(loading = false, message = "账单不存在或已删除")
            return@launch
        }
        val accounts = repository.getAccounts(current.bookId)
        val categories = repository.getCategories(current.bookId)
        val tagsById = repository.getTags(current.bookId).associateBy { it.id }
        val tagNames = repository.getTransactionTagsForTransaction(transactionId).mapNotNull { tagsById[it.tagId]?.name }
        val merchants = repository.getMerchants(current.bookId).associateBy { it.id }
        val projects = repository.getProjects(current.bookId).associateBy { it.id }
        val members = repository.getMemberships(current.bookId).associateBy { it.userId }
        _state.value = TransactionDetailState(
            loading = false,
            transaction = current,
            accounts = accounts,
            categories = categories,
            tagNames = tagNames,
            splits = repository.getTransactionSplits(transactionId),
            merchantName = current.merchantId?.let { merchants[it]?.name },
            projectName = current.projectId?.let { projects[it]?.name },
            memberName = current.memberId?.let { members[it]?.displayName },
            attachments = repository.getAttachmentsForTransaction(transactionId),
        )
    }

    fun update(amount: String, accountId: String, categoryId: String?, note: String, date: String) {
        val current = _state.value.transaction ?: return
        viewModelScope.launch {
            val result = runCatching {
                val amountMinor = requireNotNull(amount.toBigDecimalOrNull()) { "金额格式不正确" }
                    .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
                val zone = ZoneId.of("Asia/Shanghai")
                val time = Instant.ofEpochMilli(current.occurredAt).atZone(zone).toLocalTime()
                val occurredAt = LocalDate.parse(date).atTime(time).atZone(zone).toInstant().toEpochMilli()
                repository.updateTransaction(EditTransactionInput(transactionId, amountMinor, accountId, categoryId, note, occurredAt))
            }
            if (result.isSuccess) load() else _state.value = _state.value.copy(message = result.exceptionOrNull()?.message ?: "编辑失败")
        }
    }

    fun delete() = viewModelScope.launch {
        runCatching { repository.deleteTransaction(transactionId) }
            .onSuccess { _state.value = _state.value.copy(deleted = true) }
            .onFailure { _state.value = _state.value.copy(message = it.message ?: "删除失败") }
    }
}
