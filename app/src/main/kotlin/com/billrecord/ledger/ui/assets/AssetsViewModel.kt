package com.billrecord.ledger.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.RecurringTemplate
import com.billrecord.ledger.data.local.AccountBalance
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.RecurringRuleEntity
import com.billrecord.ledger.data.local.SavingGoalEntity
import com.billrecord.ledger.data.local.InstallmentPlanEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AssetsUiState(
    val bookId: String? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val balances: Map<String, AccountBalance> = emptyMap(),
    val goals: List<SavingGoalEntity> = emptyList(),
    val recurringRules: List<RecurringRuleEntity> = emptyList(),
    val installmentPlans: List<InstallmentPlanEntity> = emptyList(),
)

@HiltViewModel
class AssetsViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    val state = repository.observeSelectedBookId().filterNotNull().flatMapLatest { bookId ->
        combine(
            repository.observeAccounts(bookId),
            repository.observeAccountBalances(bookId),
            repository.observeSavingGoals(bookId),
            repository.observeRecurringRules(bookId),
            repository.observeInstallmentPlans(bookId),
        ) { accounts, balances, goals, rules, installments -> AssetsUiState(bookId, accounts, balances.associateBy { it.accountId }, goals, rules, installments) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState())

    fun addAccount(name: String, type: AccountType, openingYuan: String, creditLimitYuan: String, statementDay: String, repaymentDay: String) = viewModelScope.launch {
        val bookId = state.value.bookId ?: return@launch
        repository.createAccount(
            bookId, name, type, openingYuan.toMinor(),
            creditLimitMinor = creditLimitYuan.takeIf(String::isNotBlank)?.toMinor(),
            statementDay = statementDay.toIntOrNull(),
            repaymentDay = repaymentDay.toIntOrNull(),
        )
    }

    fun addGoal(name: String, targetYuan: String, isWish: Boolean) = viewModelScope.launch {
        val bookId = state.value.bookId ?: return@launch
        repository.createSavingGoal(bookId, name, targetYuan.toMinor(), isWish = isWish)
    }

    fun addRecurring(name: String, amountYuan: String, accountId: String, frequency: String) = viewModelScope.launch {
        val bookId = state.value.bookId ?: return@launch
        repository.createRecurringRule(
            bookId = bookId,
            name = name,
            template = RecurringTemplate(TransactionType.EXPENSE, amountYuan.toMinor(), accountId, note = name),
            frequency = frequency,
            firstRunAt = System.currentTimeMillis(),
        )
    }

    fun addInstallment(name: String, totalYuan: String, count: String, accountId: String, firstDueDate: String) = viewModelScope.launch {
        val bookId = state.value.bookId ?: return@launch
        val dueAt = runCatching {
            java.time.LocalDate.parse(firstDueDate).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        }.getOrElse { System.currentTimeMillis() }
        repository.createInstallmentPlan(bookId, accountId, name, totalYuan.toMinor(), count.toIntOrNull() ?: 0, dueAt)
    }

    fun markInstallmentPaid(planId: String) = viewModelScope.launch { repository.advanceInstallment(planId) }

    private fun String.toMinor(): Long = (trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExact() ?: 0L)
}
