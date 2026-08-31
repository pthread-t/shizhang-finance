package com.billrecord.ledger.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.billrecord.ledger.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupManifest(
    val formatVersion: Int = 3,
    val createdAt: String,
    val tableCounts: Map<String, Long>,
    val attachmentCount: Int,
)

data class RestorePreview(
    val manifest: BackupManifest,
    val currentTableCounts: Map<String, Long>,
) {
    val changedTables: Int get() = manifest.tableCounts.count { (table, count) -> currentTableCounts[table] != count }
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val json: Json,
) {
    suspend fun create(password: CharArray, output: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "备份密码至少需要 8 位" }
        val tempZip = File.createTempFile("ledger-backup-", ".zip", context.cacheDir)
        try {
            val manifest = writeLogicalZip(tempZip)
            encrypt(tempZip.inputStream(), output, password)
            manifest
        } finally {
            tempZip.delete()
            password.fill('\u0000')
        }
    }

    suspend fun preview(password: CharArray, input: InputStream): RestorePreview = withContext(Dispatchers.IO) {
        val tempZip = File.createTempFile("ledger-preview-", ".zip", context.cacheDir)
        try {
            decrypt(input, tempZip.outputStream(), password)
            ZipFile(tempZip).use { zip ->
                val entry = requireNotNull(zip.getEntry("manifest.json")) { "备份清单缺失" }
                val manifest = json.decodeFromString<BackupManifest>(zip.getInputStream(entry).bufferedReader().readText())
                require(manifest.formatVersion in 1..3) { "不支持的备份格式版本" }
                val sqlite = database.openHelper.readableDatabase
                val current = TABLES.associateWith { table -> sqlite.query("SELECT COUNT(*) FROM `$table`").use { if (it.moveToFirst()) it.getLong(0) else 0L } }
                RestorePreview(manifest, current)
            }
        } finally {
            tempZip.delete()
            password.fill('\u0000')
        }
    }

    suspend fun restore(password: CharArray, input: InputStream, replace: Boolean): BackupManifest = withContext(Dispatchers.IO) {
        val tempZip = File.createTempFile("ledger-restore-", ".zip", context.cacheDir)
        try {
            decrypt(input, tempZip.outputStream(), password)
            ZipFile(tempZip).use { zip ->
                val manifest = json.decodeFromString<BackupManifest>(
                    zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader().readText(),
                )
                require(manifest.formatVersion in 1..3) { "不支持的备份格式版本" }
                val sqlite = database.openHelper.writableDatabase
                sqlite.beginTransaction()
                try {
                    if (replace) TABLES.asReversed().forEach { table -> sqlite.execSQL("DELETE FROM `$table`") }
                    TABLES.forEach { table ->
                        zip.getEntry("tables/$table.jsonl")?.let { entry ->
                            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                                lines.filter(String::isNotBlank).forEach { line -> insertRow(sqlite, table, json.parseToJsonElement(line) as JsonObject) }
                            }
                        }
                    }
                    sqlite.setTransactionSuccessful()
                } finally {
                    sqlite.endTransaction()
                }
                restoreAttachments(zip)
                manifest
            }
        } finally {
            tempZip.delete()
            password.fill('\u0000')
        }
    }

    private fun writeLogicalZip(target: File): BackupManifest {
        val sqlite = database.openHelper.readableDatabase
        val counts = TABLES.associateWith { table -> sqlite.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getLong(0) else 0L } }
        val attachmentsRoot = File(context.filesDir, "attachments")
        val attachments = if (attachmentsRoot.exists()) attachmentsRoot.walkTopDown().filter(File::isFile).toList() else emptyList()
        val manifest = BackupManifest(createdAt = Instant.now().toString(), tableCounts = counts, attachmentCount = attachments.size)

        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json.encodeToString(manifest).toByteArray()); zip.closeEntry()
            TABLES.forEach { table ->
                zip.putNextEntry(ZipEntry("tables/$table.jsonl"))
                val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                sqlite.query("SELECT * FROM $table").use { cursor ->
                    while (cursor.moveToNext()) {
                        writer.write(cursorRow(cursor).toString())
                        writer.newLine()
                    }
                }
                writer.flush()
                zip.closeEntry()
            }
            attachments.forEach { file ->
                val relative = file.relativeTo(attachmentsRoot).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("attachments/$relative")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return manifest
    }

    private fun cursorRow(cursor: Cursor): JsonObject = JsonObject(
        cursor.columnNames.associateWith { column ->
            val index = cursor.getColumnIndexOrThrow(column)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP))
                else -> JsonPrimitive(cursor.getString(index))
            }
        },
    )

    private fun insertRow(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, table: String, row: JsonObject) {
        val values = ContentValues()
        row.forEach { (column, element) ->
            if (element is JsonNull) values.putNull(column)
            else {
                val primitive = element as JsonPrimitive
                when {
                    primitive.isString -> values.put(column, primitive.content)
                    primitive.longOrNull != null -> values.put(column, primitive.longOrNull)
                    primitive.doubleOrNull != null -> values.put(column, primitive.doubleOrNull)
                    primitive.booleanOrNull != null -> values.put(column, primitive.booleanOrNull)
                    else -> values.put(column, primitive.contentOrNull)
                }
            }
        }
        sqlite.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    private fun restoreAttachments(zip: ZipFile) {
        val root = File(context.filesDir, "attachments").canonicalFile
        zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("attachments/") }.forEach { entry ->
            val relative = entry.name.removePrefix("attachments/")
            val target = File(root, relative).canonicalFile
            require(target.path.startsWith(root.path + File.separator)) { "非法附件路径" }
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { source -> target.outputStream().use(source::copyTo) }
        }
    }

    private fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        DataOutputStream(BufferedOutputStream(output)).use { data ->
            data.write(MAGIC); data.write(salt); data.write(iv); data.flush()
            CipherOutputStream(data, cipher).use { encrypted -> input.use { it.copyTo(encrypted) } }
        }
    }

    private fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val data = DataInputStream(BufferedInputStream(input))
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "不是有效的拾账备份" }
        val salt = ByteArray(16).also(data::readFully)
        val iv = ByteArray(12).also(data::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        CipherInputStream(data, cipher).use { decrypted -> BufferedOutputStream(output).use(decrypted::copyTo) }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 180_000, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private companion object {
        val MAGIC = "BILLBACKUP1\n".toByteArray()
        val TABLES = listOf(
            "books", "memberships", "accounts", "categories", "transactions", "transaction_splits", "postings", "tags", "transaction_tags",
            "attachments", "budgets", "recurring_rules", "installment_plans", "saving_goals", "merchants", "projects",
            "saved_filters", "audit_events", "outbox_operations", "sync_cursors", "sync_conflicts",
            "ai_provider_profiles", "ai_conversations", "ai_messages",
        )
    }
}
