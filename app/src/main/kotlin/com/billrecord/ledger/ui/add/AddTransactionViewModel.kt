package com.billrecord.ledger.ui.add

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.automation.LocalOcrService
import com.billrecord.ledger.automation.ParsedEntry
import com.billrecord.ledger.automation.SmartEntryParser
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.RecordInput
import com.billrecord.ledger.data.RecordSplitInput
import com.billrecord.ledger.data.attachment.AttachmentRepository
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class AddUiState(
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: String = "",
    val currency: String = "CNY",
    val exchangeRate: String = "1",
    val destinationAmount: String = "",
    val destinationCurrency: String = "CNY",
    val note: String = "",
    val selectedAccountId: String? = null,
    val selectedDestinationAccountId: String? = null,
    val selectedCategoryId: String? = null,
    val reimbursable: Boolean = false,
    val refundOfTransactionId: String? = null,
    val selectedTagIds: Set<String> = emptySet(),
    val selectedMerchantId: String? = null,
    val selectedProjectId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val recognizedText: String? = null,
    val attachmentUris: List<Uri> = emptyList(),
    val savedTransactionId: String? = null,
    val splits: List<SplitLineUi> = emptyList(),
    val baseCurrency: String = "CNY",
    val currencyExpanded: Boolean = false,
)

data class SplitLineUi(
    val id: String = java.util.UUID.randomUUID().toString(),
    val categoryId: String? = null,
    val amount: String = "",
)

@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val ocrService: LocalOcrService,
    private val attachmentRepository: AttachmentRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val selectedBookId = repository.observeSelectedBookId().filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state
    val speechConsentAccepted = preferences.speechConsentAccepted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val accounts = selectedBookId.flatMapLatest { repository.observeAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AccountEntity>())
    val categories = kotlinx.coroutines.flow.combine(selectedBookId, _state) { bookId, state -> bookId to state.type }
        .flatMapLatest { (bookId, type) -> repository.observeCategories(bookId, if (type == TransactionType.INCOME) TransactionType.INCOME else TransactionType.EXPENSE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<CategoryEntity>())
    val recentExpenses = selectedBookId.flatMapLatest { repository.observeRecentTransactions(it) }
        .map { values -> values.filter { it.type == TransactionType.EXPENSE }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.TransactionEntity>())
    val tags = selectedBookId.flatMapLatest { repository.observeTagUsage(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.TagUsage>())
    val merchants = selectedBookId.flatMapLatest { repository.observeMerchantUsage(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.DimensionUsage>())
    val projects = selectedBookId.flatMapLatest { repository.observeProjectUsage(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<com.billrecord.ledger.data.local.DimensionUsage>())

    init {
        viewModelScope.launch {
            accounts.collect { values ->
                if (values.isNotEmpty() && _state.value.selectedAccountId == null) setAccount(values.first().id)
            }
        }
    }

    fun setType(value: TransactionType) = update { copy(type = value, selectedCategoryId = null, splits = emptyList(), error = null) }
    fun setAmount(value: String) = update { copy(amount = value.filter { it.isDigit() || it == '.' }.take(12), error = null) }
    fun setCurrency(value: String) {
        val currency = value.filter(Char::isLetter).uppercase().take(3)
        update { copy(currency = currency, error = null) }
        if (currency.length == 3) viewModelScope.launch { applyCurrency(currency) }
    }
    fun setExchangeRate(value: String) = update { copy(exchangeRate = value.filter { it.isDigit() || it == '.' }.take(14), error = null) }
    fun setDestinationAmount(value: String) = update { copy(destinationAmount = value.filter { it.isDigit() || it == '.' }.take(12), error = null) }
    fun setDestinationCurrency(value: String) = update { copy(destinationCurrency = value.filter(Char::isLetter).uppercase().take(3), error = null) }
    fun setNote(value: String) = update { copy(note = value.take(200), error = null) }
    fun setAccount(value: String) {
        val account = accounts.value.firstOrNull { it.id == value }
        update { copy(selectedAccountId = value) }
        account?.let { viewModelScope.launch { applyCurrency(it.currency) } }
    }
    fun toggleCurrencyExpanded() = update { copy(currencyExpanded = !currencyExpanded) }
    fun setDestinationAccount(value: String) = update { copy(selectedDestinationAccountId = value) }
    fun setCategory(value: String) = update { copy(selectedCategoryId = value) }
    fun setReimbursable(value: Boolean) = update { copy(reimbursable = value) }
    fun setRefundOf(value: String?) {
        val original = recentExpenses.value.firstOrNull { it.id == value }
        update { copy(refundOfTransactionId = value, selectedCategoryId = original?.categoryId ?: selectedCategoryId, error = null) }
    }
    fun toggleTag(id: String) = update { copy(selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id) }
    fun setMerchant(id: String?) = update { copy(selectedMerchantId = id) }
    fun setProject(id: String?) = update { copy(selectedProjectId = id) }
    fun createCategory(name: String, parentId: String?) = viewModelScope.launch {
        val id = repository.createCategory(selectedBookId.value, name, if (_state.value.type == TransactionType.INCOME) TransactionType.INCOME else TransactionType.EXPENSE, parentId)
        setCategory(id)
    }
    fun createTag(name: String) = viewModelScope.launch { toggleTag(repository.createTag(selectedBookId.value, name)) }
    fun createMerchant(name: String) = viewModelScope.launch { setMerchant(repository.createMerchant(selectedBookId.value, name)) }
    fun createProject(name: String) = viewModelScope.launch { setProject(repository.createProject(selectedBookId.value, name)) }
    fun reportError(message: String) = update { copy(error = message) }
    fun acceptSpeechConsent() = viewModelScope.launch { preferences.acceptSpeechConsent() }
    fun addAttachments(values: List<Uri>) = update { copy(attachmentUris = (attachmentUris + values).distinct().take(8), error = null) }

    fun beginSplit() {
        val total = parseMinor(_state.value.amount)
        val first = total?.div(2)
        val second = total?.minus(first ?: 0)
        val options = categories.value
        update {
            copy(
                splits = listOf(
                    SplitLineUi(categoryId = options.getOrNull(0)?.id, amount = first?.toYuan().orEmpty()),
                    SplitLineUi(categoryId = options.getOrNull(1)?.id ?: options.firstOrNull()?.id, amount = second?.toYuan().orEmpty()),
                ),
                error = null,
            )
        }
    }

    fun addSplitLine() = update { copy(splits = splits + SplitLineUi(categoryId = categories.value.firstOrNull()?.id), error = null) }
    fun stopSplit() = update { copy(splits = emptyList(), error = null) }
    fun removeSplitLine(id: String) = update { copy(splits = splits.filterNot { it.id == id }, error = null) }
    fun setSplitCategory(id: String, categoryId: String) = update { copy(splits = splits.map { if (it.id == id) it.copy(categoryId = categoryId) else it }, error = null) }
    fun setSplitAmount(id: String, amount: String) = update {
        copy(splits = splits.map { if (it.id == id) it.copy(amount = amount.filter { value -> value.isDigit() || value == '.' }.take(12)) else it }, error = null)
    }

    fun parseText(text: String) = applyParsed(SmartEntryParser.parse(text), text)

    fun parseImage(uri: Uri) {
        viewModelScope.launch {
            runCatching { ocrService.recognize(uri) }
                .onSuccess { parseText(it) }
                .onFailure { update { copy(error = "没有识别出账单内容，请手动填写") } }
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        current.savedTransactionId?.let { transactionId ->
            update { copy(saving = true, error = null) }
            viewModelScope.launch { finishAttachments(transactionId, onSaved) }
            return
        }
        val amountMinor = parseMinor(current.amount)
        if (amountMinor == null || amountMinor <= 0) { update { copy(error = "请输入有效金额") }; return }
        if (!current.currency.matches(Regex("[A-Z]{3}"))) { update { copy(error = "币种需使用 3 位代码，例如 CNY、USD") }; return }
        val rate = current.exchangeRate.toBigDecimalOrNull()
        if (rate == null || rate.signum() <= 0) { update { copy(error = "请输入有效汇率") }; return }
        val baseAmountMinor = BigDecimal(amountMinor).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val accountId = current.selectedAccountId ?: accounts.value.firstOrNull()?.id
        if (accountId == null) { update { copy(error = "请选择账户") }; return }
        if (current.type == TransactionType.TRANSFER && current.selectedDestinationAccountId == null) {
            update { copy(error = "请选择转入账户") }; return
        }
        val destinationAmountMinor = if (current.type == TransactionType.TRANSFER) parseMinor(current.destinationAmount.ifBlank { current.amount }) else null
        if (current.type == TransactionType.TRANSFER && (destinationAmountMinor == null || destinationAmountMinor <= 0 || !current.destinationCurrency.matches(Regex("[A-Z]{3}")))) {
            update { copy(error = "请输入有效的转入金额和币种") }; return
        }
        val rawSplits = current.splits.map { line ->
            val value = parseMinor(line.amount)
            if (value == null || value <= 0) { update { copy(error = "每个拆分项都需要有效金额") }; return }
            line to value
        }
        if (rawSplits.isNotEmpty() && (rawSplits.size < 2 || rawSplits.sumOf { it.second } != amountMinor)) {
            update { copy(error = "至少需要两个拆分项，且拆分合计必须等于账单金额") }
            return
        }
        var allocatedBase = 0L
        val splitInputs = rawSplits.mapIndexed { index, (line, value) ->
            val splitBase = if (index == rawSplits.lastIndex) baseAmountMinor - allocatedBase
            else BigDecimal(value).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact().also { allocatedBase += it }
            RecordSplitInput(line.categoryId, value, splitBase)
        }
        if (splitInputs.any { it.baseAmountMinor <= 0 }) {
            update { copy(error = "汇率换算后拆分金额过小，请调整拆分项") }
            return
        }
        update { copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                preferences.setExchangeRate(selectedBookId.value, current.currency, rate.stripTrailingZeros().toPlainString())
                repository.createTransaction(
                    RecordInput(
                        bookId = selectedBookId.value,
                        type = current.type,
                        amountMinor = amountMinor,
                        accountId = accountId,
                        categoryId = current.selectedCategoryId ?: categories.value.firstOrNull()?.id,
                        destinationAccountId = current.selectedDestinationAccountId,
                        destinationAmountMinor = destinationAmountMinor,
                        currency = current.currency,
                        destinationCurrency = if (current.type == TransactionType.TRANSFER) current.destinationCurrency else null,
                        exchangeRate = rate.stripTrailingZeros().toPlainString(),
                        baseAmountMinor = baseAmountMinor,
                        note = current.note,
                        reimbursementStatus = if (current.reimbursable) ReimbursementStatus.PENDING else ReimbursementStatus.NONE,
                        refundOfTransactionId = current.refundOfTransactionId,
                        splits = splitInputs,
                        tagIds = current.selectedTagIds,
                        merchantId = current.selectedMerchantId,
                        projectId = current.selectedProjectId,
                    ),
                )
            }.onSuccess { transactionId ->
                update { copy(savedTransactionId = transactionId) }
                finishAttachments(transactionId, onSaved)
            }.onFailure { error -> update { copy(saving = false, error = error.message ?: "保存失败") } }
        }
    }

    private suspend fun finishAttachments(transactionId: String, onSaved: () -> Unit) {
        val bookId = selectedBookId.value
        runCatching {
            _state.value.attachmentUris.toList().forEach { uri ->
                attachmentRepository.add(bookId, transactionId, uri)
                update { copy(attachmentUris = attachmentUris - uri) }
            }
        }.onSuccess {
            onSaved()
        }.onFailure { error ->
            update {
                copy(
                    saving = false,
                    error = "账单已经保存，但附件处理失败：${error.message ?: "请检查文件后重试"}",
                )
            }
        }
    }

    private fun applyParsed(parsed: ParsedEntry, original: String) {
        val accountId = accounts.value.firstOrNull { it.name.contains(parsed.accountHint.orEmpty()) }?.id
        val categoryId = categories.value.firstOrNull { it.name.contains(parsed.categoryHint.orEmpty()) }?.id
        update {
            copy(
                type = parsed.type,
                amount = parsed.amountMinor?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() } ?: amount,
                note = parsed.note.ifBlank { note },
                selectedAccountId = accountId ?: selectedAccountId,
                selectedCategoryId = categoryId ?: selectedCategoryId,
                recognizedText = original.take(300),
            )
        }
    }

    private inline fun update(block: AddUiState.() -> AddUiState) { _state.value = _state.value.block() }
    private suspend fun applyCurrency(currency: String) {
        val book = repository.getBook(selectedBookId.value)
        val base = book?.baseCurrency ?: "CNY"
        val remembered = if (currency == base) "1" else preferences.exchangeRate(selectedBookId.value, currency).first().orEmpty()
        update {
            copy(
                currency = currency,
                baseCurrency = base,
                exchangeRate = remembered,
                currencyExpanded = currency != base && remembered.isBlank(),
                error = null,
            )
        }
    }
    private fun parseMinor(value: String): Long? = runCatching {
        BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
    private fun Long.toYuan() = BigDecimal(this).movePointLeft(2).stripTrailingZeros().toPlainString()
}
