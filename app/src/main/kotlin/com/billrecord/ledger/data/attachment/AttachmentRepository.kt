package com.billrecord.ledger.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.local.AttachmentEntity
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.OutboxOperationEntity
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.SyncOperationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LedgerDao,
    private val json: Json,
    private val preferences: AppPreferences,
) {
    suspend fun add(bookId: String, transactionId: String, source: Uri): String = withContext(Dispatchers.IO) {
        requireCanEdit(bookId)
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "attachments/$bookId").apply { mkdirs() }.canonicalFile
        val root = File(context.filesDir, "attachments").canonicalFile
        require(folder.path.startsWith(root.path + File.separator))
        val temporary = File(folder, "$id.copying")
        val target = File(folder, id)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            context.contentResolver.openInputStream(source)?.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= MAX_BYTES) { "单个附件不能超过 10 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } ?: error("无法读取所选附件")
            require(size > 0) { "附件不能为空" }
            java.nio.file.Files.move(temporary.toPath(), target.toPath())
            val now = System.currentTimeMillis()
            val entity = AttachmentEntity(
                id, bookId, transactionId, displayName(source), context.contentResolver.getType(source) ?: "application/octet-stream",
                target.absolutePath, sha256 = digest.digest().joinToString("") { "%02x".format(it) }, sizeBytes = size, updatedAt = now,
            )
            dao.upsertAttachment(entity)
            val payload = buildJsonObject {
                put("id", id); put("transactionId", transactionId); put("displayName", entity.displayName); put("mimeType", entity.mimeType)
                put("sha256", entity.sha256); put("sizeBytes", entity.sizeBytes)
            }
            dao.enqueue(OutboxOperationEntity(UUID.randomUUID().toString(), bookId, SyncEntityType.ATTACHMENT, id, SyncOperationType.UPSERT, 0, json.encodeToString(payload.keys), payload.toString(), now, now))
            id
        } catch (error: Throwable) {
            if (temporary.exists()) temporary.delete()
            if (target.exists()) target.delete()
            throw error
        }
    }

    suspend fun markUploaded(id: String, remoteKey: String) {
        val current = dao.getAttachment(id) ?: return
        if (current.remoteKey == remoteKey) return
        val now = System.currentTimeMillis()
        dao.upsertAttachment(current.copy(remoteKey = remoteKey, updatedAt = now))
        val payload = buildJsonObject { put("remoteKey", remoteKey) }
        dao.enqueue(
            OutboxOperationEntity(
                UUID.randomUUID().toString(), current.bookId, SyncEntityType.ATTACHMENT, id, SyncOperationType.UPSERT,
                current.version, json.encodeToString(payload.keys), payload.toString(), now, now,
            ),
        )
    }

    suspend fun storeDownloaded(attachment: AttachmentEntity, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() == attachment.sizeBytes) { "附件下载长度不匹配" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual.equals(attachment.sha256, true)) { "附件下载校验失败" }
        val folder = File(context.filesDir, "attachments/${attachment.bookId}").apply { mkdirs() }.canonicalFile
        val root = File(context.filesDir, "attachments").canonicalFile
        require(folder.path.startsWith(root.path + File.separator))
        val target = File(folder, attachment.id)
        target.outputStream().use { it.write(bytes) }
        dao.upsertAttachment(attachment.copy(localUri = target.absolutePath))
    }

    private fun displayName(uri: Uri): String {
        val fromCursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return (fromCursor ?: uri.lastPathSegment ?: "附件").take(255)
    }

    private suspend fun requireCanEdit(bookId: String) {
        val userId = preferences.userId() ?: return
        val membership = dao.getMembership(bookId, userId) ?: return
        require(membership.deletedAt == null) { "您已不再是该账本成员" }
        require(membership.role != com.billrecord.shared.BookRole.VIEWER) { "当前成员角色为只读，不能添加附件" }
    }

    private companion object { const val MAX_BYTES = 10L * 1024 * 1024 }
}
