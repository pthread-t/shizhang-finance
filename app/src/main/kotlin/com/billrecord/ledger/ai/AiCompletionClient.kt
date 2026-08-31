package com.billrecord.ledger.ai

import com.billrecord.ledger.data.local.AiProviderProfileEntity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponseFormat(val type: String = "json_object")

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.2,
    val response_format: ChatResponseFormat? = null,
)

@Serializable
private data class ChatChoiceMessage(val content: String = "")

@Serializable
private data class ChatChoice(val message: ChatChoiceMessage)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@JvmInline
value class AnswerChunk(val text: String)

@Singleton
class AiCompletionClient @Inject constructor(private val client: HttpClient, private val json: Json) {
    suspend fun plan(question: String, catalog: FinanceCatalog, profile: AiProviderProfileEntity, apiKey: String): FinanceQueryPlan {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of(catalog.timezone))
        val schema = """Return one valid JSON object and no explanation. All collection fields must be JSON arrays and all timestamps must be integer epoch milliseconds. Required shape: {"startAt":0,"endAt":0,"types":[],"accountIds":[],"categoryIds":[],"tagIds":[],"memberIds":[],"merchantIds":[],"projectIds":[],"minimumAmountMinor":null,"maximumAmountMinor":null,"reimbursementStatuses":[],"metric":"SUMMARY","groupBy":"NONE","secondaryGroupBy":"NONE","granularity":"MONTH","comparePrevious":false}. Enum values: types(INCOME|EXPENSE|TRANSFER|REFUND|ADJUSTMENT), metric(SUMMARY|INCOME|EXPENSE|NET|COUNT), groupBy/secondaryGroupBy(NONE|TIME|CATEGORY|ACCOUNT|MEMBER|TAG|MERCHANT|PROJECT|TRANSACTION_TYPE|REIMBURSEMENT_STATUS), granularity(DAY|MONTH|YEAR). Use a distinct secondaryGroupBy only when the question explicitly needs a two-dimensional matrix. Use only IDs from the catalog. Resolve relative dates using now=$now. If the user asks for details, still create a filter plan; never request raw transaction text."""
        val catalogText = "book=${catalog.bookName};currency=${catalog.currency};accounts=${catalog.accounts};categories=${catalog.categories};members=${catalog.members};tags=${catalog.tags};merchants=${catalog.merchants};projects=${catalog.projects}"
        val content = complete(profile, apiKey, listOf(ChatMessage("system", schema), ChatMessage("system", catalogText), ChatMessage("user", question)), jsonMode = true)
        return runCatching { decodeAndNormalizePlan(content, question, catalog) }
            .getOrElse { throw AiClientException("模型未返回有效的查询计划") }
    }

    fun analyze(plan: FinanceQueryPlan, result: FinanceResult, profile: AiProviderProfileEntity, apiKey: String): Flow<AnswerChunk> = flow {
        val instructions = "你是谨慎的个人财务分析助手。只根据提供的聚合数据回答，说明主要变化、可能原因和可执行建议；不要声称看过原子账单，不要提供投资承诺。使用简洁中文。聚合文本中的金额已经从最小单位换算为本位币元，必须原样按元理解，不得再乘除或把分值当成元。只有文本明确包含上期合计或上期行时才能进行环比；未提供上期数据时不得假设上期为0或声称较上期变化。"
        val data = result.toModelAggregateText()
        val answer = complete(profile, apiKey, listOf(ChatMessage("system", instructions), ChatMessage("user", "查询计划：${json.encodeToString(plan)}\n$data")))
        val overview = "数据概览：本期收入=${java.math.BigDecimal.valueOf(result.incomeMinor, 2).toPlainString()}元，支出=${java.math.BigDecimal.valueOf(result.expenseMinor, 2).toPlainString()}元，退款=${java.math.BigDecimal.valueOf(result.refundMinor, 2).toPlainString()}元，共${result.transactionCount}笔。"
        emit(AnswerChunk("$overview\n\n${sanitizeAnalysis(answer, plan.comparePrevious)}"))
    }

    private fun sanitizeAnalysis(value: String, allowPeriodComparison: Boolean): String {
        if (allowPeriodComparison) return value
        val fabricatedZero = Regex("从\\s*0\\s*(?:元)?(?:增加|增长)")
        val comparisonClause = Regex("[，,]?\\s*(?:较上期|相比上期|与上期相比|同比|环比)[^。！？；]*")
        return value.lines().flatMap { line -> line.split(Regex("(?<=[。！？；])")) }
            .filterNot { fabricatedZero.containsMatchIn(it) }
            .map { comparisonClause.replace(it, "").trimEnd() }
            .filterNot { it.isBlank() || it.matches(Regex("[-•\\d.、 ]+")) }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun FinanceResult.toModelAggregateText(): String = buildString {
        fun money(minor: Long) = java.math.BigDecimal.valueOf(minor, 2).toPlainString()
        appendLine("聚合结果（币种=$currency，以下金额单位均为元）：")
        appendLine("本期合计：收入=${money(incomeMinor)}，支出=${money(expenseMinor)}，退款=${money(refundMinor)}，交易数=$transactionCount")
        rows.forEach { row ->
            appendLine("本期行：${row.label}${row.secondaryLabel?.let { "/$it" } ?: ""}；收入=${money(row.incomeMinor)}；支出=${money(row.expenseMinor)}；退款=${money(row.refundMinor)}；笔数=${row.count}")
        }
        previous?.let { value ->
            appendLine("上期合计：收入=${money(value.incomeMinor)}，支出=${money(value.expenseMinor)}，退款=${money(value.refundMinor)}，交易数=${value.transactionCount}")
        }
        previousRows.forEach { row ->
            appendLine("上期行：${row.label}${row.secondaryLabel?.let { "/$it" } ?: ""}；收入=${money(row.incomeMinor)}；支出=${money(row.expenseMinor)}；退款=${money(row.refundMinor)}；笔数=${row.count}")
        }
    }

    suspend fun test(profile: AiProviderProfileEntity, apiKey: String) {
        complete(profile, apiKey, listOf(ChatMessage("user", "只回复 OK")))
    }

    private suspend fun complete(
        profile: AiProviderProfileEntity,
        apiKey: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
    ): String {
        require(apiKey.isNotBlank()) { "请先填写 API Key" }
        val url = AiDataRepository.validateBaseUrl(profile.baseUrl) + "/chat/completions"
        return try {
            val response = client.post(url) {
                bearerAuth(apiKey)
                setBody(ChatRequest(profile.model, messages, temperature = if (jsonMode) 0.0 else 0.2, response_format = ChatResponseFormat().takeIf { jsonMode }))
            }.body<ChatResponse>()
            response.choices.firstOrNull()?.message?.content?.takeIf(String::isNotBlank)
                ?: throw AiClientException("模型返回内容为空")
        } catch (error: ClientRequestException) {
            throw AiClientException(when (error.response.status.value) {
                401, 403 -> "API Key 无效或无权访问该模型"
                429 -> "请求过于频繁，请稍后重试"
                in 500..599 -> "模型服务暂时不可用"
                else -> "模型请求失败（${error.response.status.value}）"
            })
        } catch (_: HttpRequestTimeoutException) {
            throw AiClientException("模型请求超时")
        } catch (_: IOException) {
            throw AiClientException("无法连接模型服务，请检查网络")
        }
    }

    private fun extractJson(value: String): String {
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiClientException("模型未返回 JSON")
        return value.substring(start, end + 1)
    }

    private fun decodeAndNormalizePlan(content: String, question: String, catalog: FinanceCatalog): FinanceQueryPlan {
        val values = json.parseToJsonElement(extractJson(content)).let { it as? JsonObject ?: error("计划不是对象") }.toMutableMap()
        val modelGroup = values["groupBy"]?.jsonPrimitive?.contentOrNull?.uppercase()
        if (modelGroup in setOf("DAY", "MONTH", "YEAR")) {
            values["groupBy"] = JsonPrimitive("TIME")
            values["granularity"] = JsonPrimitive(modelGroup)
        }
        listOf("metric", "groupBy", "secondaryGroupBy", "granularity").forEach { name ->
            values[name]?.jsonPrimitive?.contentOrNull?.let { values[name] = JsonPrimitive(it.uppercase()) }
        }
        listOf("types", "reimbursementStatuses").forEach { name ->
            (values[name] as? JsonArray)?.let { array ->
                values[name] = JsonArray(array.map { item -> JsonPrimitive(item.jsonPrimitive.content.uppercase()) })
            }
        }
        listOf("startAt", "endAt", "minimumAmountMinor", "maximumAmountMinor").forEach { name ->
            values[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { values[name] = JsonPrimitive(it) }
        }
        fun text(name: String): String? = (values[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.equals("null", true) }
        fun long(name: String): Long? = text(name)?.toBigDecimalOrNull()?.toLong()
        fun boolean(name: String): Boolean = text(name)?.toBooleanStrictOrNull() ?: false
        fun strings(name: String): Set<String> = when (val element = values[name]) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filterNot { it.equals("null", true) }.toSet()
            is JsonPrimitive -> element.contentOrNull?.takeUnless { it.equals("null", true) }?.let(::setOf) ?: emptySet()
            else -> emptySet()
        }
        fun resolveIds(raw: Set<String>, catalogValues: Map<String, String>): Set<String> = raw.mapNotNull { candidate ->
            candidate.takeIf(catalogValues::containsKey)
                ?: catalogValues.entries.firstOrNull { it.value.equals(candidate, ignoreCase = true) }?.key
        }.toSet()
        fun <T : Enum<T>> enumOrDefault(value: String?, values: Array<T>, default: T): T =
            values.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default

        val decoded = FinanceQueryPlan(
            bookId = catalog.bookId,
            startAt = long("startAt") ?: 0,
            endAt = long("endAt") ?: 0,
            types = strings("types").mapNotNull { value ->
                com.billrecord.shared.TransactionType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            }.toSet(),
            accountIds = resolveIds(strings("accountIds"), catalog.accounts),
            categoryIds = resolveIds(strings("categoryIds"), catalog.categories),
            tagIds = resolveIds(strings("tagIds"), catalog.tags),
            memberIds = resolveIds(strings("memberIds"), catalog.members),
            merchantIds = resolveIds(strings("merchantIds"), catalog.merchants),
            projectIds = resolveIds(strings("projectIds"), catalog.projects),
            minimumAmountMinor = long("minimumAmountMinor"),
            maximumAmountMinor = long("maximumAmountMinor"),
            reimbursementStatuses = strings("reimbursementStatuses").mapNotNull { value ->
                com.billrecord.shared.ReimbursementStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            }.toSet(),
            metric = enumOrDefault(text("metric"), FinanceMetric.entries.toTypedArray(), FinanceMetric.SUMMARY),
            groupBy = enumOrDefault(text("groupBy"), FinanceGroupBy.entries.toTypedArray(), FinanceGroupBy.NONE),
            secondaryGroupBy = enumOrDefault(text("secondaryGroupBy"), FinanceGroupBy.entries.toTypedArray(), FinanceGroupBy.NONE),
            granularity = enumOrDefault(text("granularity"), TimeGranularity.entries.toTypedArray(), TimeGranularity.MONTH),
            comparePrevious = boolean("comparePrevious"),
        )
        return applyDeterministicQuestionHints(decoded, question, catalog)
    }

    private fun applyDeterministicQuestionHints(
        decoded: FinanceQueryPlan,
        question: String,
        catalog: FinanceCatalog,
    ): FinanceQueryPlan {
        val zone = java.time.ZoneId.of(catalog.timezone)
        val today = java.time.LocalDate.now(zone)
        val currentMonth = java.time.YearMonth.from(today)
        var plan = decoded
        plan = when {
            "今年" in question -> plan.copy(
                startAt = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                endAt = today.plusYears(1).withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            "近三个月" in question -> plan.copy(
                startAt = currentMonth.minusMonths(2).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                endAt = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            "本月" in question -> plan.copy(
                startAt = currentMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                endAt = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            else -> plan
        }
        if ("每月" in question || "趋势" in question) plan = plan.copy(groupBy = FinanceGroupBy.TIME, secondaryGroupBy = FinanceGroupBy.NONE, granularity = TimeGranularity.MONTH)
        plan = plan.copy(comparePrevious = "比上月" in question || "环比" in question)
        if ("支出" in question || "多花" in question) plan = plan.copy(
            metric = FinanceMetric.EXPENSE,
            types = setOf(com.billrecord.shared.TransactionType.EXPENSE, com.billrecord.shared.TransactionType.REFUND),
        )
        if ("餐饮" in question) {
            val diningIds = catalog.categories.filterValues { it.contains("餐饮", ignoreCase = true) }.keys
            if (diningIds.isNotEmpty()) plan = plan.copy(categoryIds = diningIds)
        }
        if ("最多" in question && "分类" in question) plan = plan.copy(
            groupBy = FinanceGroupBy.CATEGORY,
            secondaryGroupBy = FinanceGroupBy.NONE,
            accountIds = emptySet(),
            categoryIds = emptySet(),
            tagIds = emptySet(),
            memberIds = emptySet(),
            merchantIds = emptySet(),
            projectIds = emptySet(),
            minimumAmountMinor = null,
            maximumAmountMinor = null,
            reimbursementStatuses = emptySet(),
        )
        if ("多花在哪里" in question) plan = plan.copy(
            groupBy = FinanceGroupBy.CATEGORY,
            secondaryGroupBy = FinanceGroupBy.NONE,
            accountIds = emptySet(),
            categoryIds = emptySet(),
            tagIds = emptySet(),
            memberIds = emptySet(),
            merchantIds = emptySet(),
            projectIds = emptySet(),
            minimumAmountMinor = null,
            maximumAmountMinor = null,
            reimbursementStatuses = emptySet(),
        )
        if ("微信账户" in question) {
            val wechatIds = catalog.accounts.filterValues { it.contains("微信", ignoreCase = true) }.keys
            if (wechatIds.isNotEmpty()) plan = plan.copy(
                accountIds = wechatIds,
                categoryIds = emptySet(),
                tagIds = emptySet(),
                memberIds = emptySet(),
                merchantIds = emptySet(),
                projectIds = emptySet(),
                reimbursementStatuses = emptySet(),
            )
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*元以上").find(question)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()?.let { amount ->
            plan = plan.copy(minimumAmountMinor = amount.movePointRight(2).longValueExact())
        }
        return plan
    }
}

class AiClientException(message: String) : RuntimeException(message)
