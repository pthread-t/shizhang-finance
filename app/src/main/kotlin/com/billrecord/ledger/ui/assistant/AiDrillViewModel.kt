package com.billrecord.ledger.ui.assistant

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.billrecord.ledger.ai.AiChartFactory
import com.billrecord.ledger.ai.AiDataRepository
import com.billrecord.ledger.ai.ChartDescriptor
import com.billrecord.ledger.ai.DrillLevel
import com.billrecord.ledger.ai.DrillPoint
import com.billrecord.ledger.ai.DrillPresentation
import com.billrecord.ledger.ai.FinanceGroupBy
import com.billrecord.ledger.ai.FinanceCatalog
import com.billrecord.ledger.ai.FinanceQueryPlan
import com.billrecord.ledger.ai.TimeGranularity
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.AttachmentEntity
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.data.local.TransactionSplitEntity
import com.billrecord.shared.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class AiTransactionDetail(
    val transaction: TransactionEntity,
    val accountName: String?,
    val destinationAccountName: String?,
    val categoryName: String?,
    val memberName: String?,
    val merchantName: String?,
    val projectName: String?,
    val tagNames: List<String>,
    val splits: List<Pair<TransactionSplitEntity, String?>>,
    val attachments: List<AttachmentEntity>,
)

data class AiDrillUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val levels: List<DrillLevel> = emptyList(),
    val chart: ChartDescriptor? = null,
    val detail: AiTransactionDetail? = null,
    val accounts: Map<String, String> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiDrillViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val aiRepository: AiDataRepository,
    private val ledgerRepository: LedgerRepository,
    private val dao: LedgerDao,
    private val json: Json,
) : ViewModel() {
    private val messageId: String = requireNotNull(savedStateHandle["messageId"])
    private val initialPointId: String = requireNotNull(savedStateHandle["pointId"])
    private val stackKey = "ai_drill_stack_$messageId"
    private val _state = MutableStateFlow(AiDrillUiState())
    val state = _state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiDrillUiState())
    private val currentFilter = MutableStateFlow<TransactionFilter?>(null)
    val transactions = currentFilter.flatMapLatest { filter ->
        if (filter == null) flowOf(androidx.paging.PagingData.empty()) else Pager(PagingConfig(40, prefetchDistance = 10, enablePlaceholders = false)) { ledgerRepository.pagedQuery(filter) }.flow
    }.cachedIn(viewModelScope)

    private var rootPlan: FinanceQueryPlan? = null
    private var rootChart: ChartDescriptor? = null
    private var rootCatalog: FinanceCatalog? = null

    init {
        viewModelScope.launch {
            runCatching {
                val message = requireNotNull(aiRepository.message(messageId)) { "回答已不存在" }
                rootPlan = message.queryPlanJson?.let { json.decodeFromString(it) }
                rootChart = message.chartJson?.let { json.decodeFromString(it) }
                val catalog = aiRepository.catalog(requireNotNull(rootPlan).bookId)
                rootCatalog = catalog
                val restored = savedStateHandle.get<String>(stackKey)?.let { runCatching { json.decodeFromString<List<DrillLevel>>(it) }.getOrNull() }
                _state.value = _state.value.copy(accounts = catalog.accounts, categories = catalog.categories)
                if (!restored.isNullOrEmpty()) {
                    _state.value = _state.value.copy(levels = restored, loading = false)
                    restoreCurrent(restored.last())
                } else {
                    val point = requireNotNull(rootChart?.points?.firstOrNull { it.pointId == initialPointId }) { "下钻数据已失效" }
                    openPoint(point, point.label)
                }
            }.onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "无法打开下钻数据") }
        }
    }

    fun selectChartPoint(pointId: String) {
        val point = _state.value.chart?.points?.firstOrNull { it.pointId == pointId } ?: return
        viewModelScope.launch { openPoint(point, point.label) }
    }

    fun openTransaction(id: String) = viewModelScope.launch {
        val filter = currentFilter.value ?: return@launch
        push(DrillLevel("账单详情", filter, DrillPresentation.DETAIL, transactionId = id))
        loadDetail(id)
    }

    fun popLevel(): Boolean {
        val levels = _state.value.levels
        if (levels.size <= 1) return false
        val next = levels.dropLast(1)
        persist(next)
        viewModelScope.launch { restoreCurrent(next.last()) }
        return true
    }

    fun popTo(index: Int) {
        val levels = _state.value.levels.take(index + 1)
        if (levels.isEmpty()) return
        persist(levels)
        viewModelScope.launch { restoreCurrent(levels.last()) }
    }

    private suspend fun openPoint(point: DrillPoint, label: String) {
        val plan = requireNotNull(rootPlan)
        if (point.nextGranularity != null) {
            val childPlan = plan.copy(
                startAt = requireNotNull(point.filter.startEpochMillis),
                endAt = requireNotNull(point.filter.endEpochMillis),
                groupBy = FinanceGroupBy.TIME,
                secondaryGroupBy = FinanceGroupBy.NONE,
                granularity = point.nextGranularity,
                accountIds = point.filter.accountIds,
                categoryIds = point.filter.categoryIds,
                tagIds = point.filter.tagIds,
                memberIds = point.filter.memberIds,
                merchantIds = point.filter.merchantIds,
                projectIds = point.filter.projectIds,
                types = point.filter.types,
                reimbursementStatuses = point.filter.reimbursementStatuses,
            )
            val catalog = requireNotNull(rootCatalog)
            val chart = AiChartFactory.create(aiRepository.execute(childPlan, catalog))
            if (chart.labels.isNotEmpty()) {
                currentFilter.value = point.filter
                push(
                    DrillLevel(
                        label,
                        point.filter,
                        DrillPresentation.CHART,
                        point.pointId,
                        chartGroupBy = FinanceGroupBy.TIME,
                        chartGranularity = point.nextGranularity,
                    ),
                    chart = chart,
                )
                return
            }
        }
        categoryChildPlan(point.filter)?.let { childPlan ->
            val chart = AiChartFactory.create(aiRepository.execute(childPlan, requireNotNull(rootCatalog)))
            if (chart.labels.isNotEmpty()) {
                currentFilter.value = point.filter
                push(
                    DrillLevel(label, point.filter, DrillPresentation.CHART, point.pointId, chartGroupBy = FinanceGroupBy.CATEGORY),
                    chart = chart,
                )
                return
            }
        }
        currentFilter.value = point.filter
        push(DrillLevel(label, point.filter, DrillPresentation.TRANSACTIONS, point.pointId))
    }

    private suspend fun restoreCurrent(level: DrillLevel) {
        _state.value = _state.value.copy(loading = true, error = null, chart = null, detail = null)
        currentFilter.value = level.filter
        when (level.presentation) {
            DrillPresentation.DETAIL -> loadDetail(requireNotNull(level.transactionId))
            DrillPresentation.TRANSACTIONS -> _state.value = _state.value.copy(loading = false)
            DrillPresentation.CHART -> {
                val plan = requireNotNull(rootPlan)
                val child = if (level.chartGroupBy == FinanceGroupBy.CATEGORY) {
                    categoryChildPlan(level.filter)
                } else null
                val restoredPlan = child ?: run {
                    val next = level.chartGranularity ?: inferGranularity(level.filter, plan)
                    plan.copy(
                        startAt = level.filter.startEpochMillis ?: plan.startAt,
                        endAt = level.filter.endEpochMillis ?: plan.endAt,
                        groupBy = FinanceGroupBy.TIME,
                        secondaryGroupBy = FinanceGroupBy.NONE,
                        granularity = next,
                        accountIds = level.filter.accountIds,
                        categoryIds = level.filter.categoryIds,
                        tagIds = level.filter.tagIds,
                        memberIds = level.filter.memberIds,
                        merchantIds = level.filter.merchantIds,
                        projectIds = level.filter.projectIds,
                        types = level.filter.types,
                        reimbursementStatuses = level.filter.reimbursementStatuses,
                    )
                }
                val chart = AiChartFactory.create(aiRepository.execute(restoredPlan, requireNotNull(rootCatalog)))
                _state.value = _state.value.copy(loading = false, chart = chart)
            }
        }
    }

    private fun categoryChildPlan(filter: TransactionFilter): FinanceQueryPlan? {
        val plan = rootPlan ?: return null
        if (plan.groupBy != FinanceGroupBy.CATEGORY && plan.secondaryGroupBy != FinanceGroupBy.CATEGORY) return null
        val categoryId = filter.categoryIds.singleOrNull()?.takeUnless { it == "__NONE__" } ?: return null
        val children = rootCatalog?.categoryParents?.filterValues { it == categoryId }?.keys.orEmpty()
        if (children.isEmpty()) return null
        return plan.copy(
            startAt = filter.startEpochMillis ?: plan.startAt,
            endAt = filter.endEpochMillis ?: plan.endAt,
            groupBy = FinanceGroupBy.CATEGORY,
            secondaryGroupBy = FinanceGroupBy.NONE,
            categoryIds = children,
            accountIds = filter.accountIds,
            tagIds = filter.tagIds,
            memberIds = filter.memberIds,
            merchantIds = filter.merchantIds,
            projectIds = filter.projectIds,
            types = filter.types,
            reimbursementStatuses = filter.reimbursementStatuses,
        )
    }

    private fun inferGranularity(filter: TransactionFilter, plan: FinanceQueryPlan): TimeGranularity {
        val duration = (filter.endEpochMillis ?: plan.endAt) - (filter.startEpochMillis ?: plan.startAt)
        return if (duration > 40L * 86_400_000) TimeGranularity.MONTH else TimeGranularity.DAY
    }

    private suspend fun loadDetail(id: String) {
        val transaction = dao.getTransaction(id)
        if (transaction == null || transaction.deletedAt != null) {
            _state.value = _state.value.copy(loading = false, error = "账单已不存在或已被同步删除")
            return
        }
        val accounts = dao.getAccounts(transaction.bookId).associate { it.id to it.name }
        val categories = dao.getCategories(transaction.bookId).associate { it.id to it.name }
        val tags = dao.getTags(transaction.bookId).associate { it.id to it.name }
        val tagNames = dao.getTransactionTagsForTransaction(id).filter { it.deletedAt == null }.mapNotNull { tags[it.tagId] }
        val members = dao.getMemberships(transaction.bookId).associate { it.userId to it.displayName }
        val merchants = dao.getMerchants(transaction.bookId).associate { it.id to it.name }
        val projects = dao.getProjects(transaction.bookId).associate { it.id to it.name }
        _state.value = _state.value.copy(
            loading = false,
            error = null,
            detail = AiTransactionDetail(
                transaction,
                accounts[transaction.accountId],
                transaction.destinationAccountId?.let(accounts::get),
                transaction.categoryId?.let(categories::get),
                transaction.memberId?.let(members::get),
                transaction.merchantId?.let(merchants::get),
                transaction.projectId?.let(projects::get),
                tagNames,
                dao.getTransactionSplits(id).filter { it.deletedAt == null }.map { it to it.categoryId?.let(categories::get) },
                dao.getAttachmentsForTransaction(id).filter { it.deletedAt == null },
            ),
        )
    }

    private fun push(level: DrillLevel, chart: ChartDescriptor? = null) {
        val levels = _state.value.levels + level
        persist(levels)
        _state.value = _state.value.copy(levels = levels, loading = false, error = null, chart = chart, detail = null)
    }

    private fun persist(levels: List<DrillLevel>) {
        savedStateHandle[stackKey] = json.encodeToString(levels)
        _state.value = _state.value.copy(levels = levels)
    }
}
