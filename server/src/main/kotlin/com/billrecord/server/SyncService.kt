package com.billrecord.server

import com.billrecord.shared.BookChangedMessage
import com.billrecord.shared.BookRole
import com.billrecord.shared.RemoteChange
import com.billrecord.shared.SyncConflict
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperation
import com.billrecord.shared.SyncOperationType
import com.billrecord.shared.SyncRequest
import com.billrecord.shared.SyncResponse
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.postgresql.util.PGobject
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class StoredEntity(
    val version: Long,
    val payload: JsonObject,
    val fieldVersions: Map<String, Long>,
    val deleted: Boolean,
)

private data class ProcessedOperation(
    val userId: UUID,
    val bookId: UUID,
    val entityType: SyncEntityType,
    val entityId: UUID,
)

internal fun detectConflictingFields(
    currentVersion: Long,
    fieldVersions: Map<String, Long>,
    currentDeleted: Boolean,
    operation: SyncOperationType,
    baseVersion: Long,
    changedFields: Set<String>,
): Set<String> = when {
    currentDeleted && operation == SyncOperationType.DELETE -> emptySet()
    currentDeleted && currentVersion > baseVersion -> setOf("*")
    operation == SyncOperationType.DELETE && currentVersion > baseVersion ->
        fieldVersions.filterValues { it > baseVersion }.keys.ifEmpty { setOf("*") }
    else -> changedFields.filter { (fieldVersions[it] ?: 0) > baseVersion }.toSet()
}

class BookEventHub(private val database: Database, private val json: Json) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<UUID, MutableSet<DefaultWebSocketServerSession>>()

    fun attach(userId: UUID, session: DefaultWebSocketServerSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun detach(userId: UUID, session: DefaultWebSocketServerSession) {
        sessions[userId]?.remove(session)
    }

    fun broadcast(bookId: UUID, sequence: Long) {
        scope.launch {
            val members = database.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT user_id FROM memberships WHERE book_id = ? AND removed_at IS NULL").use { statement ->
                    statement.setObject(1, bookId)
                    statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getObject(1, UUID::class.java)) } }
                }
            }
            members.flatMap { sessions[it].orEmpty() }.forEach { session ->
                runCatching { session.send(Frame.Text(json.encodeToString(BookChangedMessage(bookId.toString(), sequence)))) }
            }
        }
    }

    fun notifyUser(userId: UUID, bookId: UUID) {
        scope.launch {
            val sequence = database.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT server_sequence FROM books WHERE id = ?").use { statement ->
                    statement.setObject(1, bookId)
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
                }
            }
            sessions[userId].orEmpty().forEach { session ->
                runCatching { session.send(Frame.Text(json.encodeToString(BookChangedMessage(bookId.toString(), sequence)))) }
            }
        }
    }
}

class SyncService(
    private val database: Database,
    private val json: Json,
    private val eventHub: BookEventHub,
) {
    fun sync(userId: UUID, authenticatedDeviceId: UUID, request: SyncRequest): SyncResponse {
        if (runCatching { UUID.fromString(request.deviceId) }.getOrNull() != authenticatedDeviceId) {
            throw ApiException(403, "device_mismatch", "同步设备与登录会话不一致")
        }
        if (request.operations.size > 100) throw ApiException(400, "sync_batch_too_large", "每次最多同步 100 个修改")
        val changedBooks = mutableMapOf<UUID, Long>()
        val response = database.transaction { connection ->
            val acknowledged = mutableListOf<String>()
            val conflicts = mutableListOf<SyncConflict>()
            val entitiesChangedEarlierInRequest = mutableSetOf<Triple<UUID, SyncEntityType, UUID>>()

            request.operations.forEach { operation ->
                val bookId = UUID.fromString(operation.bookId)
                val entityId = UUID.fromString(operation.entityId)
                val operationId = UUID.fromString(operation.operationId)
                val alreadyProcessed = connection.prepareStatement(
                    "SELECT user_id, book_id, entity_type, entity_id FROM processed_operations WHERE operation_id = ?",
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) null else ProcessedOperation(
                            result.getObject("user_id", UUID::class.java),
                            result.getObject("book_id", UUID::class.java),
                            SyncEntityType.valueOf(result.getString("entity_type")),
                            result.getObject("entity_id", UUID::class.java),
                        )
                    }
                }
                if (alreadyProcessed != null) {
                    if (
                        alreadyProcessed.userId != userId || alreadyProcessed.bookId != bookId ||
                        alreadyProcessed.entityType != operation.entityType || alreadyProcessed.entityId != entityId
                    ) {
                        throw ApiException(409, "operation_id_reuse", "operationId 已用于其他同步操作")
                    }
                    acknowledged += operation.operationId
                    return@forEach
                }

                ensureBookAndPermission(connection, userId, bookId, operation)
                val current = loadEntity(connection, bookId, operation.entityType, entityId)
                val entityKey = Triple(bookId, operation.entityType, entityId)
                val conflictingFields = if (current == null || entityKey in entitiesChangedEarlierInRequest) emptySet() else detectConflictingFields(
                    current.version, current.fieldVersions, current.deleted, operation.operation, operation.baseVersion, operation.changedFields,
                )
                if (conflictingFields.isNotEmpty()) {
                    conflicts += SyncConflict(
                        operationId = operation.operationId,
                        bookId = operation.bookId,
                        entityType = operation.entityType,
                        entityId = operation.entityId,
                        conflictingFields = conflictingFields,
                        localPayload = operation.payload,
                        serverPayload = current?.payload ?: JsonObject(emptyMap()),
                        serverVersion = current?.version ?: 0,
                        localOperation = operation.operation,
                        serverDeleted = current?.deleted ?: false,
                    )
                    return@forEach
                }

                val version = (current?.version ?: 0) + 1
                val deleted = operation.operation == SyncOperationType.DELETE
                val payload = if (deleted) current?.payload ?: operation.payload else mergePayload(current?.payload, operation)
                val fieldVersions = (current?.fieldVersions ?: emptyMap()).toMutableMap().apply {
                    if (deleted) put("*", version) else operation.changedFields.forEach { put(it, version) }
                }
                val sequence = nextSequence(connection, bookId)
                storeEntity(connection, bookId, operation.entityType, entityId, version, payload, fieldVersions, deleted)
                if (operation.entityType == SyncEntityType.ATTACHMENT) {
                    connection.prepareStatement("UPDATE attachments SET deleted_at = CASE WHEN ? THEN now() ELSE NULL END WHERE id = ? AND book_id = ?").use { statement ->
                        statement.setBoolean(1, deleted); statement.setObject(2, entityId); statement.setObject(3, bookId); statement.executeUpdate()
                    }
                }
                storeChange(connection, bookId, sequence, operation.entityType, entityId, version, payload, deleted)
                connection.prepareStatement(
                    "INSERT INTO processed_operations(operation_id, user_id, book_id, entity_type, entity_id, resulting_version) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, operationId); statement.setObject(2, userId); statement.setObject(3, bookId)
                    statement.setString(4, operation.entityType.name); statement.setObject(5, entityId); statement.setLong(6, version); statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO audit_events(book_id, actor_id, entity_type, entity_id, action, changed_fields) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                ).use { statement ->
                    statement.setObject(1, bookId); statement.setObject(2, userId); statement.setString(3, operation.entityType.name)
                    statement.setObject(4, entityId); statement.setString(5, operation.operation.name); statement.setString(6, json.encodeToString(operation.changedFields)); statement.executeUpdate()
                }
                changedBooks[bookId] = sequence
                entitiesChangedEarlierInRequest += entityKey
                acknowledged += operation.operationId
            }

            val accessibleBooks = accessibleBookIds(connection, userId)
            val changes = mutableListOf<RemoteChange>()
            val cursors = mutableMapOf<String, Long>()
            accessibleBooks.forEach { bookId ->
                val after = request.cursorByBook[bookId.toString()] ?: 0L
                val remaining = 500 - changes.size
                if (remaining <= 0) {
                    cursors[bookId.toString()] = after
                    return@forEach
                }
                val bookChanges = loadChanges(connection, bookId, after, remaining)
                changes += bookChanges
                cursors[bookId.toString()] = bookChanges.maxOfOrNull(RemoteChange::sequence) ?: currentSequence(connection, bookId)
            }
            SyncResponse(acknowledged, cursors, changes, conflicts)
        }
        changedBooks.forEach(eventHub::broadcast)
        return response
    }

    private fun ensureBookAndPermission(connection: Connection, userId: UUID, bookId: UUID, operation: SyncOperation) {
        val exists = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM books WHERE id = ?)").use { statement ->
            statement.setObject(1, bookId); statement.executeQuery().use { it.next(); it.getBoolean(1) }
        }
        if (!exists) {
            if (operation.entityType != SyncEntityType.BOOK || operation.operation != SyncOperationType.UPSERT) {
                throw ApiException(409, "book_missing", "必须先同步账本本身")
            }
            val name = (operation.payload["name"] as? JsonPrimitive)?.content ?: "家庭账本"
            connection.prepareStatement("INSERT INTO books(id, name, owner_id) VALUES (?, ?, ?)").use { statement ->
                statement.setObject(1, bookId); statement.setString(2, name.take(120)); statement.setObject(3, userId); statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO memberships(book_id, user_id, role) VALUES (?, ?, 'OWNER')").use { statement ->
                statement.setObject(1, bookId); statement.setObject(2, userId); statement.executeUpdate()
            }
        } else {
            when (operation.entityType) {
                SyncEntityType.BOOK -> FamilyService.requireRole(connection, userId, bookId, BookRole.OWNER)
                SyncEntityType.MEMBERSHIP, SyncEntityType.AUDIT_EVENT ->
                    throw ApiException(400, "managed_entity", "成员和审计记录只能通过专用接口修改")
                else -> FamilyService.requireRole(connection, userId, bookId, BookRole.EDITOR)
            }
        }
    }

    private fun accessibleBookIds(connection: Connection, userId: UUID): List<UUID> = connection.prepareStatement(
        "SELECT book_id FROM memberships WHERE user_id = ? AND removed_at IS NULL ORDER BY joined_at",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getObject(1, UUID::class.java)) } }
    }

    private fun loadEntity(connection: Connection, bookId: UUID, type: SyncEntityType, entityId: UUID): StoredEntity? =
        connection.prepareStatement("SELECT version, payload::text, field_versions::text, deleted FROM sync_entities WHERE book_id = ? AND entity_type = ? AND entity_id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, bookId); statement.setString(2, type.name); statement.setObject(3, entityId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else StoredEntity(
                    result.getLong("version"),
                    json.parseToJsonElement(result.getString("payload")).jsonObject,
                    json.parseToJsonElement(result.getString("field_versions")).jsonObject.mapValues { (_, value) -> (value as JsonPrimitive).longOrNull ?: 0 },
                    result.getBoolean("deleted"),
                )
            }
        }

    private fun mergePayload(current: JsonObject?, operation: SyncOperation): JsonObject {
        val values = current.orEmpty().toMutableMap()
        operation.changedFields.forEach { field -> operation.payload[field]?.let { values[field] = it } }
        if (current == null && operation.changedFields.isEmpty()) values.putAll(operation.payload)
        return JsonObject(values)
    }

    private fun nextSequence(connection: Connection, bookId: UUID): Long = connection.prepareStatement(
        "UPDATE books SET server_sequence = server_sequence + 1 WHERE id = ? RETURNING server_sequence",
    ).use { statement -> statement.setObject(1, bookId); statement.executeQuery().use { it.next(); it.getLong(1) } }

    private fun currentSequence(connection: Connection, bookId: UUID): Long = connection.prepareStatement("SELECT server_sequence FROM books WHERE id = ?").use { statement ->
        statement.setObject(1, bookId); statement.executeQuery().use { it.next(); it.getLong(1) }
    }

    private fun storeEntity(connection: Connection, bookId: UUID, type: SyncEntityType, entityId: UUID, version: Long, payload: JsonObject, fieldVersions: Map<String, Long>, deleted: Boolean) {
        connection.prepareStatement(
            """INSERT INTO sync_entities(book_id, entity_type, entity_id, version, payload, field_versions, deleted, updated_at)
               VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
               ON CONFLICT (book_id, entity_type, entity_id) DO UPDATE SET version=EXCLUDED.version, payload=EXCLUDED.payload,
               field_versions=EXCLUDED.field_versions, deleted=EXCLUDED.deleted, updated_at=now()""",
        ).use { statement ->
            statement.setObject(1, bookId); statement.setString(2, type.name); statement.setObject(3, entityId); statement.setLong(4, version)
            statement.setString(5, payload.toString()); statement.setString(6, json.encodeToString(fieldVersions)); statement.setBoolean(7, deleted); statement.executeUpdate()
        }
    }

    private fun storeChange(connection: Connection, bookId: UUID, sequence: Long, type: SyncEntityType, entityId: UUID, version: Long, payload: JsonObject, deleted: Boolean) {
        connection.prepareStatement(
            "INSERT INTO sync_changes(book_id, book_sequence, entity_type, entity_id, version, payload, deleted, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, now())",
        ).use { statement ->
            statement.setObject(1, bookId); statement.setLong(2, sequence); statement.setString(3, type.name); statement.setObject(4, entityId)
            statement.setLong(5, version); statement.setString(6, payload.toString()); statement.setBoolean(7, deleted); statement.executeUpdate()
        }
    }

    private fun loadChanges(connection: Connection, bookId: UUID, after: Long, limit: Int): List<RemoteChange> {
        if (limit <= 0) return emptyList()
        return connection.prepareStatement(
            "SELECT book_sequence, entity_type, entity_id, version, payload::text, deleted, updated_at FROM sync_changes WHERE book_id = ? AND book_sequence > ? ORDER BY book_sequence LIMIT ?",
        ).use { statement ->
            statement.setObject(1, bookId); statement.setLong(2, after); statement.setInt(3, limit)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    RemoteChange(
                        bookId.toString(), SyncEntityType.valueOf(result.getString("entity_type")), result.getObject("entity_id").toString(),
                        result.getLong("version"), result.getLong("book_sequence"), result.getBoolean("deleted"),
                        json.parseToJsonElement(result.getString("payload")).jsonObject, result.getTimestamp("updated_at").time,
                    ),
                )
            } }
        }
    }
}
