package com.billrecord.ledger.ui.transactions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.TransactionSort
import com.billrecord.ledger.data.EditTransactionInput
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.SavedFilterEntity
import com.billrecord.ledger.data.export.ExportFormat
import com.billrecord.ledger.data.export.ExportManager
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.TransactionFilter
import com.billrecord.shared.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val exportManager: ExportManager,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val exportMessage = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val selectedTypes = MutableStateFlow<Set<TransactionType>>(emptySet())
    val advancedFilter = MutableStateFlow(TransactionFilter())
    val sort = preferences.transactionSort.map { value -> runCatching { TransactionSort.valueOf(value) }.getOrDefault(TransactionSort.DATE_DESC) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionSort.DATE_DESC)
    val selectedBookId = repository.observeSelectedBookId().filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val accounts = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AccountEntity>())
    val categories = selectedBookId.flatMapLatest { bookId ->
        if (bookId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else combine(
            repository.observeCategories(bookId, TransactionType.EXPENSE),
            repository.observeCategories(bookId, TransactionType.INCOME),
        ) { a, b -> a + b }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<CategoryEntity>())
    val savedFilters = selectedBookId.flatMapLatest { bookId ->
        if (bookId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeSavedFilters(bookId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<SavedFilterEntity>())
    val tags = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeTags(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.TagEntity>())
    val members = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeMemberships(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.MembershipEntity>())
    val merchants = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeMerchants(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.MerchantEntity>())
    val projects = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeProjects(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.ProjectEntity>())
    val splitTransactionIds = selectedBookId.flatMapLatest { if (it.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeSplitTransactionIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recycleBin = selectedBookId.flatMapLatest { bookId ->
        if (bookId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.observeDeletedTransactions(bookId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.TransactionEntity>())

    val activeFilter = combine(selectedBookId, query, selectedTypes, advancedFilter) { bookId, text, types, advanced ->
        advanced.copy(bookIds = setOf(bookId), types = types, query = text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionFilter())

    val transactions = combine(activeFilter, sort) { filter, sorting -> filter to sorting }.flatMapLatest { (filter, sorting) ->
        Pager(PagingConfig(pageSize = 40, prefetchDistance = 10, enablePlaceholders = false)) {
            repository.pagedQuery(filter, sorting)
        }.flow
    }.cachedIn(viewModelScope)

    fun selectType(type: TransactionType?) {
        selectedTypes.value = if (type == null) emptySet() else setOf(type)
    }

    fun selectSort(value: TransactionSort) = viewModelScope.launch { preferences.setTransactionSort(value) }

    fun toggleAccount(id: String) = updateAdvanced { copy(accountIds = accountIds.toggle(id)) }
    fun toggleCategory(id: String) = updateAdvanced { copy(categoryIds = categoryIds.toggle(id)) }
    fun toggleTag(id: String) = updateAdvanced { copy(tagIds = tagIds.toggle(id)) }
    fun toggleMember(id: String) = updateAdvanced { copy(memberIds = memberIds.toggle(id)) }
    fun toggleMerchant(id: String) = updateAdvanced { copy(merchantIds = merchantIds.toggle(id)) }
    fun toggleProject(id: String) = updateAdvanced { copy(projectIds = projectIds.toggle(id)) }
    fun setReimbursement(value: ReimbursementStatus?) = updateAdvanced { copy(reimbursementStatuses = value?.let(::setOf) ?: emptySet()) }

    fun setRange(start: String, end: String): Boolean {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val startMillis = if (start.isBlank()) null else runCatching { java.time.LocalDate.parse(start).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() ?: return false
        val endMillis = if (end.isBlank()) null else runCatching { java.time.LocalDate.parse(end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() ?: return false
        updateAdvanced { copy(startEpochMillis = startMillis, endEpochMillis = endMillis) }
        return true
    }

    fun setAmountRange(minimum: String, maximum: String): Boolean {
        fun parse(value: String): Long? = if (value.isBlank()) null else runCatching { value.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
        val min = parse(minimum); val max = parse(maximum)
        if (minimum.isNotBlank() && min == null || maximum.isNotBlank() && max == null || min != null && max != null && min > max) return false
        updateAdvanced { copy(minimumAmountMinor = min, maximumAmountMinor = max) }
        return true
    }

    fun resetAdvanced() { advancedFilter.value = TransactionFilter() }

    fun saveCurrentFilter(name: String) = viewModelScope.launch {
        if (name.isNotBlank() && selectedBookId.value.isNotBlank()) repository.createSavedFilter(selectedBookId.value, name, activeFilter.value)
    }

    fun applySavedFilter(value: SavedFilterEntity) {
        val filter = repository.decodeSavedFilter(value)
        applyInitialFilter(filter)
    }

    fun applyInitialFilter(filter: TransactionFilter) {
        query.value = filter.query
        selectedTypes.value = filter.types
        advancedFilter.value = filter.copy(bookIds = emptySet(), types = emptySet(), query = "")
    }

    fun delete(transactionId: String) {
        viewModelScope.launch { repository.deleteTransaction(transactionId) }
    }

    fun restore(transactionId: String) {
        viewModelScope.launch { repository.restoreTransaction(transactionId) }
    }

    fun batchSetReimbursement(transactionIds: Set<String>, status: ReimbursementStatus) {
        viewModelScope.launch { repository.updateReimbursementStatus(transactionIds, status) }
    }

    fun batchDelete(transactionIds: Set<String>) {
        viewModelScope.launch { transactionIds.forEach { repository.deleteTransaction(it) } }
    }

    fun editTransaction(transactionId: String, amount: String, accountId: String, categoryId: String?, note: String, date: String, originalOccurredAt: Long) {
        viewModelScope.launch {
            val amountMinor = amount.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.longValueExact() ?: return@launch
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val originalTime = java.time.Instant.ofEpochMilli(originalOccurredAt).atZone(zone).toLocalTime()
            val occurredAt = runCatching { java.time.LocalDate.parse(date).atTime(originalTime).atZone(zone).toInstant().toEpochMilli() }.getOrNull() ?: return@launch
            runCatching { repository.updateTransaction(EditTransactionInput(transactionId, amountMinor, accountId, categoryId, note, occurredAt)) }
        }
    }

    fun exportCurrent(uri: Uri, format: ExportFormat) = viewModelScope.launch {
        exportMessage.value = null
        runCatching { exportManager.exportToUri(context, uri, activeFilter.value, sort.value, format) }
            .onSuccess { exportMessage.value = "导出完成" }
            .onFailure { exportMessage.value = it.message ?: "导出失败，请稍后重试" }
    }

    fun clearExportMessage() { exportMessage.value = null }

    private inline fun updateAdvanced(block: TransactionFilter.() -> TransactionFilter) { advancedFilter.value = advancedFilter.value.block() }
    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
}
