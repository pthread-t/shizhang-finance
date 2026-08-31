package com.billrecord.ledger.sync

import androidx.room.withTransaction
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.attachment.AttachmentRepository
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.AttachmentEntity
import com.billrecord.ledger.data.local.AuditEventEntity
import com.billrecord.ledger.data.local.BookEntity
import com.billrecord.ledger.data.local.BudgetEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.data.local.InstallmentPlanEntity
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.MembershipEntity
import com.billrecord.ledger.data.local.MerchantEntity
import com.billrecord.ledger.data.local.OutboxOperationEntity
import com.billrecord.ledger.data.local.PostingEntity
import com.billrecord.ledger.data.local.ProjectEntity
import com.billrecord.ledger.data.local.RecurringRuleEntity
import com.billrecord.ledger.data.local.SavedFilterEntity
import com.billrecord.ledger.data.local.SavingGoalEntity
import com.billrecord.ledger.data.local.SyncConflictEntity
import com.billrecord.ledger.data.local.SyncCursorEntity
import com.billrecord.ledger.data.local.TagEntity
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.ledger.data.local.TransactionSplitEntity
import com.billrecord.ledger.data.local.TransactionTagEntity
import com.billrecord.shared.AccountType
import com.billrecord.shared.BookRole
import com.billrecord.shared.RefreshRequest
import com.billrecord.shared.ReimbursementStatus
import com.billrecord.shared.RemoteChange
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperation
import com.billrecord.shared.SyncRequest
import com.billrecord.shared.SyncResponse
import com.billrecord.shared.TransactionType
import com.billrecord.shared.InitAttachmentUploadRequest
import com.billrecord.shared.AttachmentUploadSession
import com.billrecord.shared.MyMembershipDto
import com.billrecord.shared.MemberDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncStatus { LOCAL_ONLY, IDLE, SYNCING, ERROR }

@Singleton
class SyncRepository @Inject constructor(
    private val client: HttpClient,
    private val preferences: AppPreferences,
    private val database: AppDatabase,
    private val dao: LedgerDao,
    private val json: Json,
    private val attachmentRepository: AttachmentRepository,
) {
    val status = MutableStateFlow(if (preferences.accessToken() == null) SyncStatus.LOCAL_ONLY else SyncStatus.IDLE)
    val conflicts: Flow<List<SyncConflictEntity>> = dao.observeConflicts()

    suspend fun synchronize(): Boolean {
        var token = preferences.accessToken() ?: run { status.value = SyncStatus.LOCAL_ONLY; return true }
        status.value = SyncStatus.SYNCING
        return runCatching {
            val pending = dao.pendingOperations()
            val cursors = dao.getSyncCursors().associate { it.bookId to it.cursor }
            val request = syncRequest(pending, cursors)
            suspend fun send(requestToSend: SyncRequest): SyncResponse {
                return try {
                    postSync(token, requestToSend)
                } catch (error: ClientRequestException) {
                    when (error.response.status.value) {
                        401 -> {
                            token = refreshSession()
                            postSync(token, requestToSend)
                        }
                        403 -> {
                            val editableBooks = refreshMemberships(token)
                            val denied = requestToSend.operations.filter { it.bookId !in editableBooks }.map { it.operationId }
                            if (denied.isNotEmpty()) dao.markOperationsFailed(denied, "permission_denied")
                            throw error
                        }
                        else -> throw error
                    }
                }
            }
            var response = send(request)
            apply(response)
            refreshMemberships(token)
            if (uploadPendingAttachments(token) > 0) {
                val afterUpload = dao.pendingOperations()
                if (afterUpload.isNotEmpty()) {
                    response = send(syncRequest(afterUpload, dao.getSyncCursors().associate { it.bookId to it.cursor }))
                    apply(response)
                }
            }
            var pullRounds = 0
            while (response.changes.size >= 500 && pullRounds < 20) {
                response = send(
                    SyncRequest(preferences.deviceId(), dao.getSyncCursors().associate { it.bookId to it.cursor }, emptyList()),
                )
                apply(response)
                pullRounds++
            }
            downloadMissingAttachments(token)
            status.value = SyncStatus.IDLE
            true
        }.getOrElse { error ->
            if (error is ClientRequestException && error.response.status.value == 401) {
                preferences.clearSession()
            }
            status.value = SyncStatus.ERROR
            false
        }
    }

    private suspend fun postSync(token: String, request: SyncRequest): SyncResponse = client.post(baseUrl() + "/api/v1/sync") {
        bearerAuth(token)
        setBody(request)
    }.body()

    private fun syncRequest(operations: List<OutboxOperationEntity>, cursors: Map<String, Long>) = SyncRequest(
        deviceId = preferences.deviceId(),
        cursorByBook = cursors,
        operations = operations.map { operation ->
            SyncOperation(
                operation.operationId, operation.bookId, operation.entityType, operation.entityId, operation.operation,
                operation.baseVersion, json.decodeFromString<Set<String>>(operation.changedFieldsJson),
                json.parseToJsonElement(operation.payloadJson).jsonObject, operation.clientModifiedAt,
            )
        },
    )

    private suspend fun uploadPendingAttachments(token: String): Int {
        var completed = 0
        val base = baseUrl()
        dao.pendingAttachmentUploads().forEach { attachment ->
            val file = java.io.File(attachment.localUri)
            if (!file.isFile || file.length() != attachment.sizeBytes) return@forEach
            var session: AttachmentUploadSession = client.post(base + "/api/v1/attachments/uploads") {
                bearerAuth(token)
                setBody(InitAttachmentUploadRequest(attachment.id, attachment.bookId, attachment.transactionId, attachment.displayName, attachment.mimeType, attachment.sha256, attachment.sizeBytes))
            }.body()
            java.io.RandomAccessFile(file, "r").use { input ->
                while (!session.completed) {
                    input.seek(session.offset)
                    val count = minOf(session.chunkSize.toLong(), attachment.sizeBytes - session.offset).toInt()
                    val bytes = ByteArray(count)
                    input.readFully(bytes)
                    session = client.post(base + "/api/v1/attachments/uploads/${session.uploadId}/chunks") {
                        bearerAuth(token)
                        header("X-Upload-Offset", session.offset)
                        contentType(ContentType.Application.OctetStream)
                        setBody(bytes)
                    }.body()
                }
            }
            val result = requireNotNull(session.result) { "附件上传完成但服务器未返回结果" }
            attachmentRepository.markUploaded(attachment.id, result.remoteKey)
            completed++
        }
        return completed
    }

    private suspend fun downloadMissingAttachments(token: String) {
        val base = baseUrl()
        dao.missingAttachmentDownloads().forEach { attachment ->
            val bytes: ByteArray = client.get(base + "/api/v1/attachments/${attachment.id}") { bearerAuth(token) }.body()
            attachmentRepository.storeDownloaded(attachment, bytes)
        }
    }

    private suspend fun refreshSession(): String {
        val refresh = requireNotNull(preferences.refreshToken())
        val response: com.billrecord.shared.AuthResponse = client.post(baseUrl() + "/api/v1/auth/refresh") {
            setBody(RefreshRequest(refresh, preferences.deviceId()))
        }.body()
        preferences.saveSession(response.userId, response.accessToken, response.refreshToken)
        return response.accessToken
    }

    private suspend fun refreshMemberships(token: String): Set<String> {
        val userId = preferences.userId() ?: return emptySet()
        val remote: List<MyMembershipDto> = client.get(baseUrl() + "/api/v1/memberships") { bearerAuth(token) }.body()
        val membersByBook: Map<String, List<MemberDto>> = remote.associate { membership ->
            membership.bookId to client.get(baseUrl() + "/api/v1/books/${membership.bookId}/members") { bearerAuth(token) }.body()
        }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val existing = dao.getMembershipsForUser(userId).associateBy { it.bookId }
            val activeBookIds = remote.mapTo(mutableSetOf()) { it.bookId }
            val updated = buildList {
                remote.forEach { membership ->
                    add(
                        existing[membership.bookId]?.copy(
                            displayName = "我",
                            role = membership.role,
                            updatedAt = now,
                            deletedAt = null,
                        ) ?: MembershipEntity(membership.bookId, userId, "我", membership.role, now),
                    )
                }
                existing.values.filter { it.bookId !in activeBookIds && it.deletedAt == null }.forEach { add(it.copy(updatedAt = now, deletedAt = now)) }
            }
            if (updated.isNotEmpty()) dao.upsertMemberships(updated)
            val sharedMembers = membersByBook.flatMap { (bookId, members) ->
                members.map { member ->
                    MembershipEntity(
                        bookId = bookId,
                        userId = member.userId,
                        displayName = when (member.username) {
                            "tester_a" -> "体验者 A"
                            "tester_b" -> "体验者 B"
                            else -> member.username
                        },
                        role = member.role,
                        updatedAt = now,
                    )
                }
            }
            if (sharedMembers.isNotEmpty()) dao.upsertMemberships(sharedMembers)
        }
        return remote.filter { it.role != BookRole.VIEWER }.mapTo(mutableSetOf()) { it.bookId }
    }

    private suspend fun apply(response: SyncResponse) = database.withTransaction {
        response.changes.forEach { change -> applyChange(change) }
        val now = System.currentTimeMillis()
        dao.upsertSyncCursors(response.cursorByBook.map { (bookId, cursor) -> SyncCursorEntity(bookId, cursor, now) })
        dao.upsertConflicts(response.conflicts.map { conflict ->
            SyncConflictEntity(
                conflict.operationId, conflict.bookId, conflict.entityType, conflict.entityId,
                json.encodeToString(conflict.conflictingFields), conflict.localPayload.toString(), conflict.serverPayload.toString(),
                conflict.serverVersion, conflict.localOperation, conflict.serverDeleted, now,
            )
        })
        dao.acknowledgeOperations(response.acknowledgedOperationIds + response.conflicts.map { it.operationId })
    }

    suspend fun resolveConflict(conflict: SyncConflictEntity, localFields: Set<String>) = database.withTransaction {
        val local = json.parseToJsonElement(conflict.localPayloadJson).jsonObject
        val server = json.parseToJsonElement(conflict.serverPayloadJson).jsonObject
        val conflictingFields = json.decodeFromString<Set<String>>(conflict.conflictingFieldsJson)
        val useLocalAll = "*" in localFields
        val wholeEntityConflict = "*" in conflictingFields
        val retryFields = when {
            useLocalAll -> local.keys
            wholeEntityConflict -> emptySet()
            else -> (local.keys - conflictingFields) + localFields
        }
        val merged = JsonObject((server.keys + local.keys).associateWith { key ->
            if (useLocalAll || (!wholeEntityConflict && key !in conflictingFields) || key in localFields) local[key] ?: server.getValue(key)
            else server[key] ?: local.getValue(key)
        })
        val keepLocalDelete = conflict.localOperation == com.billrecord.shared.SyncOperationType.DELETE && useLocalAll
        val acceptServerDelete = conflict.serverDeleted && !useLocalAll
        applyChange(
            RemoteChange(
                conflict.bookId, conflict.entityType, conflict.entityId, conflict.serverVersion, 0,
                deleted = keepLocalDelete || acceptServerDelete,
                payload = if (keepLocalDelete) server else merged,
                serverModifiedAt = System.currentTimeMillis(),
            ),
        )
        if (keepLocalDelete || retryFields.isNotEmpty()) {
            val now = System.currentTimeMillis()
            dao.enqueue(
                OutboxOperationEntity(
                    UUID.randomUUID().toString(), conflict.bookId, conflict.entityType, conflict.entityId,
                    if (keepLocalDelete) com.billrecord.shared.SyncOperationType.DELETE else com.billrecord.shared.SyncOperationType.UPSERT,
                    conflict.serverVersion,
                    json.encodeToString(retryFields),
                    if (keepLocalDelete) "{}" else merged.toString(),
                    now, now,
                ),
            )
        }
        dao.removeConflict(conflict.operationId)
    }

    private suspend fun applyChange(change: RemoteChange) {
        val p = change.payload
        val deletedAt = if (change.deleted) change.serverModifiedAt else null
        when (change.entityType) {
            SyncEntityType.BOOK -> dao.upsertBook(
                BookEntity(change.entityId, p.string("name", "家庭账本"), p.string("baseCurrency", "CNY"), p.string("timezone", "Asia/Shanghai"), p.long("monthStartDay", 1).toInt(), 0xFF2C6E63, change.serverModifiedAt, change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.ACCOUNT -> dao.upsertAccount(
                AccountEntity(
                    change.entityId, change.bookId, p.string("name", "账户"), enumOr(AccountType.CASH, p.string("type")), p.string("currency", "CNY"), p.long("openingBalanceMinor"),
                    creditLimitMinor = p.longOrNull("creditLimitMinor"), statementDay = p.longOrNull("statementDay")?.toInt(), repaymentDay = p.longOrNull("repaymentDay")?.toInt(),
                    icon = "wallet", colorArgb = 0xFF52616B, sortOrder = 99, updatedAt = change.serverModifiedAt, version = change.version, deletedAt = deletedAt,
                ),
            )
            SyncEntityType.CATEGORY -> dao.upsertCategory(
                CategoryEntity(change.entityId, change.bookId, p.stringOrNull("parentId"), p.string("name", "分类"), enumOr(TransactionType.EXPENSE, p.string("type")), p.string("icon", "category"), 0xFFC49A4A, 99, updatedAt = change.serverModifiedAt, version = change.version, deletedAt = deletedAt),
            )
            SyncEntityType.TRANSACTION -> dao.upsertTransaction(
                TransactionEntity(
                    id = change.entityId,
                    bookId = change.bookId,
                    type = enumOr(TransactionType.EXPENSE, p.string("type")),
                    amountMinor = p.long("amountMinor"),
                    currency = p.string("currency", "CNY"),
                    baseAmountMinor = p.long("baseAmountMinor"),
                    exchangeRate = p.string("exchangeRate", "1"),
                    categoryId = p.stringOrNull("categoryId"),
                    accountId = p.string("accountId"),
                    destinationAccountId = p.stringOrNull("destinationAccountId"),
                    destinationAmountMinor = p.longOrNull("destinationAmountMinor"),
                    destinationCurrency = p.stringOrNull("destinationCurrency"),
                    memberId = p.stringOrNull("memberId"),
                    merchantId = p.stringOrNull("merchantId"),
                    projectId = p.stringOrNull("projectId"),
                    reimbursementStatus = enumOr(ReimbursementStatus.NONE, p.string("reimbursementStatus")),
                    refundOfTransactionId = p.stringOrNull("refundOfTransactionId"),
                    note = p.string("note"),
                    occurredAt = p.long("occurredAt", change.serverModifiedAt),
                    createdAt = p.long("createdAt", change.serverModifiedAt),
                    updatedAt = change.serverModifiedAt,
                    version = change.version,
                    deletedAt = deletedAt,
                ),
            )
            SyncEntityType.POSTING -> dao.upsertPostings(listOf(
                PostingEntity(change.entityId, p.string("transactionId"), change.bookId, p.string("ledgerAccountId"), p.long("amountMinor"), p.string("currency", "CNY"), p.long("baseAmountMinor"), change.serverModifiedAt, change.version, deletedAt),
            ))
            SyncEntityType.TRANSACTION_SPLIT -> dao.upsertTransactionSplits(listOf(
                TransactionSplitEntity(
                    change.entityId, p.string("transactionId"), change.bookId, p.stringOrNull("categoryId"),
                    p.long("amountMinor"), p.long("baseAmountMinor"), p.string("note"), change.serverModifiedAt, change.version, deletedAt,
                ),
            ))
            SyncEntityType.BUDGET -> dao.upsertBudget(
                BudgetEntity(change.entityId, change.bookId, p.string("name", "预算"), p.stringOrNull("categoryId"), p.string("period", "MONTH"), p.long("startAt"), p.longOrNull("endAt"), p.long("amountMinor"), p.string("currency", "CNY"), p.boolean("rollover"), p.long("alertThresholdPercent", 80).toInt(), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.SAVING_GOAL -> dao.upsertSavingGoal(
                SavingGoalEntity(change.entityId, change.bookId, p.string("name", "目标"), p.long("targetAmountMinor"), p.long("currentAmountMinor"), p.string("currency", "CNY"), p.longOrNull("targetAt"), p.boolean("isWish"), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.MEMBERSHIP -> dao.upsertMembership(
                MembershipEntity(change.bookId, p.string("userId", change.entityId), p.string("displayName", "家庭成员"), enumOr(BookRole.VIEWER, p.string("role")), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.TAG -> dao.upsertTag(
                TagEntity(change.entityId, change.bookId, p.string("name", "标签"), p.long("colorArgb", 0xFF2C6E63), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.TRANSACTION_TAG -> dao.upsertTransactionTags(listOf(
                TransactionTagEntity(p.string("transactionId"), p.string("tagId"), change.bookId, change.serverModifiedAt, change.version, deletedAt),
            ))
            SyncEntityType.ATTACHMENT -> {
                val existing = dao.getAttachment(change.entityId)
                dao.upsertAttachment(
                    AttachmentEntity(
                        change.entityId, change.bookId, p.string("transactionId"), p.string("displayName", "附件"), p.string("mimeType", "application/octet-stream"),
                        existing?.localUri ?: "", p.stringOrNull("remoteKey") ?: existing?.remoteKey, p.string("sha256"), p.long("sizeBytes"), change.serverModifiedAt, change.version, deletedAt,
                    ),
                )
            }
            SyncEntityType.RECURRING_RULE -> dao.upsertRecurringRule(
                RecurringRuleEntity(change.entityId, change.bookId, p.string("name", "周期账单"), p.string("transactionTemplateJson", "{}"), p.string("recurrenceRule", "MONTHLY"), p.long("nextRunAt"), p.boolean("enabled", true), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.INSTALLMENT_PLAN -> dao.upsertInstallmentPlan(
                InstallmentPlanEntity(change.entityId, change.bookId, p.string("accountId"), p.string("name", "分期"), p.long("totalAmountMinor"), p.long("installmentCount").toInt(), p.long("completedCount").toInt(), p.long("firstDueAt"), p.string("recurrenceRule", "MONTHLY"), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.MERCHANT -> dao.upsertMerchant(
                MerchantEntity(change.entityId, change.bookId, p.string("name", "商家"), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.PROJECT -> dao.upsertProject(
                ProjectEntity(change.entityId, change.bookId, p.string("name", "项目"), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.SAVED_FILTER -> dao.upsertSavedFilter(
                SavedFilterEntity(change.entityId, change.bookId, p.string("name", "筛选"), p.string("filterJson", "{}"), change.serverModifiedAt, change.version, deletedAt),
            )
            SyncEntityType.AUDIT_EVENT -> dao.upsertAuditEvent(
                AuditEventEntity(change.entityId, change.bookId, p.string("actorId", "server"), enumOr(SyncEntityType.AUDIT_EVENT, p.string("entityType")), p.string("entityId", change.entityId), p.string("action", "SYNC"), p.string("changedFieldsJson", "[]"), p.long("occurredAt", change.serverModifiedAt), change.version),
            )
        }
    }

    private suspend fun baseUrl() = preferences.serverUrl.first().trimEnd('/')
    private inline fun <reified T : Enum<T>> enumOr(default: T, value: String) = runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    private fun JsonObject.string(key: String, default: String = "") = (this[key] as? JsonPrimitive)?.content ?: default
    private fun JsonObject.stringOrNull(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.long(key: String, default: Long = 0) = (this[key] as? JsonPrimitive)?.longOrNull ?: default
    private fun JsonObject.longOrNull(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String, default: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
}
