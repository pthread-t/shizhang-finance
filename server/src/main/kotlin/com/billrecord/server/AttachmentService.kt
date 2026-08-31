package com.billrecord.server

import com.billrecord.shared.AttachmentUploadResponse
import com.billrecord.shared.AttachmentUploadSession
import com.billrecord.shared.BookRole
import com.billrecord.shared.InitAttachmentUploadRequest
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

data class AttachmentDownload(val file: File, val displayName: String, val mimeType: String)

class AttachmentService(private val database: Database, config: AppConfig) {
    private val root = File(config.attachmentPath).apply { mkdirs() }.canonicalFile

    fun upload(
        userId: UUID,
        bookId: UUID,
        transactionId: UUID,
        displayName: String,
        mimeType: String,
        expectedSha256: String?,
        input: InputStream,
    ): AttachmentUploadResponse {
        database.dataSource.connection.use { connection ->
            FamilyService.requireRole(connection, userId, bookId, BookRole.EDITOR)
        }
        val attachmentId = UUID.randomUUID()
        val bookFolder = safeBookFolder(bookId)
        val temporary = File(bookFolder, "$attachmentId.uploading")
        val target = File(bookFolder, attachmentId.toString())
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            DigestInputStream(input, digest).use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > MAX_ATTACHMENT_BYTES) throw ApiException(413, "attachment_too_large", "单个附件不能超过 10 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (size == 0L) throw ApiException(400, "empty_attachment", "附件不能为空")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !sha256.equals(expectedSha256, ignoreCase = true)) {
                throw ApiException(400, "attachment_hash_mismatch", "附件校验失败")
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath())
            database.transaction { connection ->
                connection.prepareStatement(
                    """INSERT INTO attachments(id, book_id, transaction_id, storage_key, display_name, mime_type, sha256, size_bytes, uploaded_by)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setObject(1, attachmentId); statement.setObject(2, bookId); statement.setObject(3, transactionId)
                    statement.setString(4, "$bookId/$attachmentId"); statement.setString(5, displayName.take(255)); statement.setString(6, mimeType.take(120))
                    statement.setString(7, sha256); statement.setLong(8, size); statement.setObject(9, userId); statement.executeUpdate()
                }
            }
            return AttachmentUploadResponse(attachmentId.toString(), "$bookId/$attachmentId", sha256, size)
        } catch (error: Throwable) {
            if (temporary.exists()) temporary.delete()
            if (target.exists()) target.delete()
            throw error
        }
    }

    fun beginUpload(userId: UUID, request: InitAttachmentUploadRequest): AttachmentUploadSession = database.transaction { connection ->
        val attachmentId = UUID.fromString(request.attachmentId)
        val bookId = UUID.fromString(request.bookId)
        val transactionId = UUID.fromString(request.transactionId)
        require(request.sizeBytes in 1..MAX_ATTACHMENT_BYTES) { "附件大小必须在 1 字节到 10 MB 之间" }
        require(request.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "附件 SHA-256 格式错误" }
        FamilyService.requireRole(connection, userId, bookId, BookRole.EDITOR)
        connection.prepareStatement(
            "SELECT book_id, transaction_id, storage_key, display_name, mime_type, sha256, size_bytes FROM attachments WHERE id = ? AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, attachmentId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    val sameIdentity = result.getObject("book_id", UUID::class.java) == bookId &&
                        result.getObject("transaction_id", UUID::class.java) == transactionId &&
                        result.getString("display_name") == request.displayName.take(255) &&
                        result.getString("mime_type") == request.mimeType.take(120) &&
                        result.getString("sha256").equals(request.sha256, true) &&
                        result.getLong("size_bytes") == request.sizeBytes
                    if (!sameIdentity) throw ApiException(409, "attachment_id_conflict", "附件 ID 已用于其他内容")
                    val response = AttachmentUploadResponse(attachmentId.toString(), result.getString("storage_key"), result.getString("sha256"), result.getLong("size_bytes"))
                    return@transaction AttachmentUploadSession(attachmentId.toString(), attachmentId.toString(), request.sizeBytes, CHUNK_SIZE, true, response)
                }
            }
        }
        connection.prepareStatement(
            """SELECT id, book_id, transaction_id, display_name, mime_type, expected_sha256, size_bytes, received_bytes, expires_at > now() AS active
               FROM attachment_uploads WHERE attachment_id = ? FOR UPDATE""",
        ).use { statement ->
            statement.setObject(1, attachmentId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    val uploadId = result.getObject("id", UUID::class.java)
                    val sameIdentity = result.getObject("book_id", UUID::class.java) == bookId &&
                        result.getObject("transaction_id", UUID::class.java) == transactionId &&
                        result.getString("display_name") == request.displayName.take(255) &&
                        result.getString("mime_type") == request.mimeType.take(120) &&
                        result.getString("expected_sha256").equals(request.sha256, true) &&
                        result.getLong("size_bytes") == request.sizeBytes
                    if (!sameIdentity) throw ApiException(409, "attachment_id_conflict", "附件 ID 已用于其他上传内容")
                    if (result.getBoolean("active")) {
                        return@transaction AttachmentUploadSession(uploadId.toString(), attachmentId.toString(), result.getLong("received_bytes"), CHUNK_SIZE, false)
                    }
                    connection.prepareStatement("DELETE FROM attachment_uploads WHERE id = ?").use { expired ->
                        expired.setObject(1, uploadId)
                        expired.executeUpdate()
                    }
                    File(safeBookFolder(bookId), "$uploadId.uploading").delete()
                }
            }
        }
        val uploadId = UUID.randomUUID()
        connection.prepareStatement(
            """INSERT INTO attachment_uploads(id, attachment_id, book_id, transaction_id, display_name, mime_type, expected_sha256, size_bytes, uploaded_by)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.setObject(1, uploadId); statement.setObject(2, attachmentId); statement.setObject(3, bookId); statement.setObject(4, transactionId)
            statement.setString(5, request.displayName.take(255)); statement.setString(6, request.mimeType.take(120)); statement.setString(7, request.sha256.lowercase())
            statement.setLong(8, request.sizeBytes); statement.setObject(9, userId); statement.executeUpdate()
        }
        AttachmentUploadSession(uploadId.toString(), attachmentId.toString(), 0, CHUNK_SIZE, false)
    }

    fun uploadStatus(userId: UUID, uploadId: UUID): AttachmentUploadSession = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT attachment_id, book_id, received_bytes FROM attachment_uploads WHERE id = ? AND expires_at > now()").use { statement ->
            statement.setObject(1, uploadId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ApiException(404, "upload_not_found", "上传会话不存在或已过期")
                FamilyService.requireRole(connection, userId, result.getObject("book_id", UUID::class.java), BookRole.EDITOR)
                AttachmentUploadSession(uploadId.toString(), result.getObject("attachment_id").toString(), result.getLong("received_bytes"), CHUNK_SIZE, false)
            }
        }
    }

    fun appendChunk(userId: UUID, uploadId: UUID, offset: Long, input: InputStream): AttachmentUploadSession {
        var temporaryToDelete: File? = null
        var targetToDeleteOnFailure: File? = null
        val response = try {
            database.transaction { connection ->
            val upload = connection.prepareStatement(
                """SELECT attachment_id, book_id, transaction_id, display_name, mime_type, expected_sha256, size_bytes, received_bytes
                   FROM attachment_uploads WHERE id = ? AND expires_at > now() FOR UPDATE""",
            ).use { statement ->
                statement.setObject(1, uploadId)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else UploadRow(
                        result.getObject("attachment_id", UUID::class.java), result.getObject("book_id", UUID::class.java), result.getObject("transaction_id", UUID::class.java),
                        result.getString("display_name"), result.getString("mime_type"), result.getString("expected_sha256"), result.getLong("size_bytes"), result.getLong("received_bytes"),
                    )
                }
            } ?: throw ApiException(404, "upload_not_found", "上传会话不存在或已过期")
            FamilyService.requireRole(connection, userId, upload.bookId, BookRole.EDITOR)
            if (offset != upload.receivedBytes) throw ApiException(409, "upload_offset_mismatch", "上传偏移应为 ${upload.receivedBytes}")
            val bookFolder = safeBookFolder(upload.bookId)
            val temporary = File(bookFolder, "$uploadId.uploading").canonicalFile
            if (temporary.exists() && temporary.length() > upload.receivedBytes) {
                java.io.RandomAccessFile(temporary, "rw").use { it.setLength(upload.receivedBytes) }
            }
            if (temporary.exists() && temporary.length() < upload.receivedBytes) throw ApiException(409, "upload_state_corrupt", "上传临时文件状态不一致，请重新发起")
            val bytes = readChunk(input)
            if (bytes.isEmpty()) throw ApiException(400, "empty_chunk", "分块不能为空")
            if (upload.receivedBytes + bytes.size > upload.sizeBytes) throw ApiException(400, "chunk_exceeds_size", "分块超过声明的附件大小")
            FileOutputStream(temporary, true).use { it.write(bytes) }
            val nextOffset = upload.receivedBytes + bytes.size
            if (nextOffset < upload.sizeBytes) {
                connection.prepareStatement("UPDATE attachment_uploads SET received_bytes = ?, expires_at = now() + interval '24 hours' WHERE id = ?").use { statement ->
                    statement.setLong(1, nextOffset); statement.setObject(2, uploadId); statement.executeUpdate()
                }
                return@transaction AttachmentUploadSession(uploadId.toString(), upload.attachmentId.toString(), nextOffset, CHUNK_SIZE, false)
            }
            val sha256 = temporary.inputStream().use(::sha256)
            if (!sha256.equals(upload.expectedSha256, true)) {
                java.io.RandomAccessFile(temporary, "rw").use { it.setLength(upload.receivedBytes) }
                throw ApiException(400, "attachment_hash_mismatch", "附件校验失败，最后分块已回退")
            }
            val target = File(bookFolder, upload.attachmentId.toString()).canonicalFile
            java.nio.file.Files.copy(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            targetToDeleteOnFailure = target
            connection.prepareStatement(
                """INSERT INTO attachments(id, book_id, transaction_id, storage_key, display_name, mime_type, sha256, size_bytes, uploaded_by)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET storage_key=EXCLUDED.storage_key, display_name=EXCLUDED.display_name, mime_type=EXCLUDED.mime_type, sha256=EXCLUDED.sha256, size_bytes=EXCLUDED.size_bytes, deleted_at=NULL""",
            ).use { statement ->
                statement.setObject(1, upload.attachmentId); statement.setObject(2, upload.bookId); statement.setObject(3, upload.transactionId)
                statement.setString(4, "${upload.bookId}/${upload.attachmentId}"); statement.setString(5, upload.displayName); statement.setString(6, upload.mimeType)
                statement.setString(7, sha256); statement.setLong(8, upload.sizeBytes); statement.setObject(9, userId); statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM attachment_uploads WHERE id = ?").use { it.setObject(1, uploadId); it.executeUpdate() }
            temporaryToDelete = temporary
            val result = AttachmentUploadResponse(upload.attachmentId.toString(), "${upload.bookId}/${upload.attachmentId}", sha256, upload.sizeBytes)
            AttachmentUploadSession(uploadId.toString(), upload.attachmentId.toString(), nextOffset, CHUNK_SIZE, true, result)
            }
        } catch (error: Throwable) {
            targetToDeleteOnFailure?.delete()
            throw error
        }
        targetToDeleteOnFailure = null
        temporaryToDelete?.delete()
        return response
    }

    fun download(userId: UUID, attachmentId: UUID): AttachmentDownload = database.dataSource.connection.use { connection ->
        val metadata = connection.prepareStatement(
            "SELECT book_id, storage_key, display_name, mime_type FROM attachments WHERE id = ? AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, attachmentId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else listOf(result.getString("book_id"), result.getString("storage_key"), result.getString("display_name"), result.getString("mime_type"))
            }
        } ?: throw ApiException(404, "attachment_not_found", "附件不存在")
        FamilyService.requireRole(connection, userId, UUID.fromString(metadata[0]), BookRole.VIEWER)
        val file = File(root, metadata[1]).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) throw ApiException(404, "attachment_file_missing", "附件文件不存在")
        AttachmentDownload(file, metadata[2], metadata[3])
    }

    private fun safeBookFolder(bookId: UUID): File = File(root, bookId.toString()).apply { mkdirs() }.canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) }

    private fun readChunk(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream(CHUNK_SIZE)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, CHUNK_SIZE + 1 - total))
            if (count < 0) break
            total += count
            if (total > CHUNK_SIZE) throw ApiException(413, "chunk_too_large", "单个分块不能超过 $CHUNK_SIZE 字节")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) >= 0) Unit
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class UploadRow(
        val attachmentId: UUID, val bookId: UUID, val transactionId: UUID, val displayName: String, val mimeType: String,
        val expectedSha256: String, val sizeBytes: Long, val receivedBytes: Long,
    )

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
        const val CHUNK_SIZE = 512 * 1024
    }
}
