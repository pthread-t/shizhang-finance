package com.billrecord.ledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.BudgetEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.MonthlySummary
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.data.local.PeriodSummary
import com.billrecord.shared.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val selectedBookId: String? = null,
    val books: List<BookEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val recent: List<TransactionEntity> = emptyList(),
    val summary: MonthlySummary = MonthlySummary(0, 0, 0),
    val budgets: List<BudgetEntity> = emptyList(),
    val trend: List<PeriodSummary> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    private val selectedBook = repository.observeSelectedBookId().filterNotNull()

    private val accounts = selectedBook.flatMapLatest(repository::observeAccounts)
    private val categories = selectedBook.flatMapLatest { bookId ->
        kotlinx.coroutines.flow.combine(
            repository.observeCategories(bookId, TransactionType.EXPENSE),
            repository.observeCategories(bookId, TransactionType.INCOME),
        ) { expense, income -> expense + income }
    }
    private val recent = selectedBook.flatMapLatest(repository::observeRecentTransactions)
    private val summary = selectedBook.flatMapLatest { bookId ->
        val (start, end) = repository.currentMonthRange()
        repository.observeSummary(bookId, start, end)
    }
    private val budgets = selectedBook.flatMapLatest(repository::observeBudgets)
    private val trend = selectedBook.flatMapLatest { bookId ->
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val end = java.time.YearMonth.now(zone).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = java.time.YearMonth.now(zone).minusMonths(5).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        repository.observeMonthlyTrend(bookId, start, end)
    }

    val state: StateFlow<HomeUiState> = kotlinx.coroutines.flow.combine(
        selectedBook,
        repository.observeBooks(),
        accounts,
        categories,
        recent,
        summary,
        budgets,
        trend,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            selectedBookId = values[0] as String,
            books = values[1] as List<BookEntity>,
            accounts = values[2] as List<AccountEntity>,
            categories = values[3] as List<CategoryEntity>,
            recent = values[4] as List<TransactionEntity>,
            summary = values[5] as MonthlySummary,
            budgets = values[6] as List<BudgetEntity>,
            trend = values[7] as List<PeriodSummary>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun selectBook(bookId: String) {
        viewModelScope.launch { repository.selectBook(bookId) }
    }
}
