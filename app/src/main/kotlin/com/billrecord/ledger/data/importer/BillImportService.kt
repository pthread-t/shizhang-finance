package com.billrecord.ledger.data.importer

import android.content.Context
import android.net.Uri
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.RecordInput
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.shared.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

data class ImportPreviewRow(
    val rowNumber: Int,
    val time: String,
    val type: TransactionType,
    val amountMinor: Long?,
    val category: String,
    val account: String,
    val note: String,
    val duplicate: Boolean,
    val error: String? = null,
)

data class ImportPreview(
    val source: String,
    val rows: List<ImportPreviewRow>,
) {
    val validCount get() = rows.count { it.error == null && !it.duplicate }
    val duplicateCount get() = rows.count(ImportPreviewRow::duplicate)
    val errorCount get() = rows.count { it.error != null }
}

@Singleton
class BillImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LedgerRepository,
    private val dao: LedgerDao,
) {
    suspend fun preview(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "导入文件"
        val table = if (name.endsWith(".xlsx", true)) readXlsx(uri) else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取文件")
            CsvTableParser.parse(decode(bytes))
        }
        mapRows(name, table)
    }

    suspend fun import(preview: ImportPreview): Int {
        val bookId = repository.observeSelectedBookId().first() ?: error("未选择账本")
        val accounts = repository.getAccounts(bookId)
        val categories = repository.getCategories(bookId)
        var imported = 0
        preview.rows.filter { it.error == null && !it.duplicate && it.amountMinor != null }.forEach { row ->
            val account = accounts.firstOrNull { it.name.contains(row.account) || row.account.contains(it.name) } ?: accounts.first()
            val category = categories.firstOrNull { it.type == row.type && (it.name.contains(row.category) || row.category.contains(it.name)) }
                ?: categories.firstOrNull { it.type == row.type }
            repository.createTransaction(
                RecordInput(
                    bookId = bookId,
                    type = row.type,
                    amountMinor = row.amountMinor!!,
                    accountId = account.id,
                    categoryId = category?.id,
                    note = row.note,
                    occurredAt = parseTime(row.time) ?: System.currentTimeMillis(),
                ),
            )
            imported++
        }
        return imported
    }

    private suspend fun mapRows(source: String, table: List<List<String>>): ImportPreview {
        if (table.isEmpty()) return ImportPreview(source, emptyList())
        val headers = table.first().map { it.trim().removePrefix("\uFEFF") }
        fun index(vararg names: String) = headers.indexOfFirst { header -> names.any { candidate -> header.contains(candidate, ignoreCase = true) } }
        val timeIndex = index("交易时间", "创建时间", "时间")
        val amountIndex = index("金额(元)", "交易金额", "金额")
        val typeIndex = index("收/支", "收支", "类型")
        val categoryIndex = index("交易分类", "分类", "商品")
        val accountIndex = index("支付方式", "付款方式", "账户")
        val noteIndex = index("商品说明", "交易对方", "备注", "说明")
        val sourceType = when { headers.any { it.contains("微信") } || source.contains("微信") -> "微信"; source.contains("支付宝") -> "支付宝"; else -> "通用表格" }
        val bookId = repository.observeSelectedBookId().first().orEmpty()
        val accounts = repository.getAccounts(bookId)
        return ImportPreview(sourceType, table.drop(1).mapIndexedNotNull { index, cells ->
            if (cells.all(String::isBlank)) return@mapIndexedNotNull null
            fun cell(position: Int) = cells.getOrElse(position) { "" }.trim()
            val rawAmount = cell(amountIndex).replace("¥", "").replace("￥", "").replace(",", "").trim()
            val amount = runCatching { BigDecimal(rawAmount).abs().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
            val rawType = cell(typeIndex)
            val type = if (rawType.contains("收入") || rawType == "收入") TransactionType.INCOME else TransactionType.EXPENSE
            val time = cell(timeIndex)
            val note = cell(noteIndex).ifBlank { cell(categoryIndex) }
            val accountName = cell(accountIndex).ifBlank { sourceType.takeIf { it != "通用表格" }.orEmpty() }
            val account = accounts.firstOrNull { accountName.contains(it.name) || it.name.contains(accountName) } ?: accounts.firstOrNull()
            val occurredAt = parseTime(time)
            val duplicate = if (amount != null && occurredAt != null && account != null) {
                dao.countMatchingTransactions(bookId, amount, occurredAt, account.id, note) > 0
            } else false
            ImportPreviewRow(
                rowNumber = index + 2,
                time = time,
                type = type,
                amountMinor = amount,
                category = cell(categoryIndex),
                account = accountName,
                note = note,
                duplicate = duplicate,
                error = when { amount == null -> "金额无法识别"; occurredAt == null -> "时间无法识别"; else -> null },
            )
        })
    }

    private fun readXlsx(uri: Uri): List<List<String>> {
        val temp = File.createTempFile("ledger-import-", ".xlsx", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use(input::copyTo) } ?: return emptyList()
            ZipFile(temp).use { zip ->
                val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry -> parseSharedStrings(zip.getInputStream(entry)) }.orEmpty()
                val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: return emptyList()
                return parseSheet(zip.getInputStream(sheet), shared)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parseSharedStrings(input: java.io.InputStream): List<String> {
        val parser = android.util.Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val result = mutableListOf<String>()
        var text = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") text = StringBuilder()
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") result += text.toString()
            }
            parser.next()
        }
        return result
    }

    private fun parseSheet(input: java.io.InputStream, shared: List<String>): List<List<String>> {
        val parser = android.util.Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val rows = mutableListOf<List<String>>()
        var row = sortedMapOf<Int, String>()
        var cellIndex = 0
        var sharedString = false
        var value = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = sortedMapOf()
                    "c" -> { cellIndex = columnIndex(parser.getAttributeValue(null, "r").orEmpty()); sharedString = parser.getAttributeValue(null, "t") == "s"; value = "" }
                    "v", "t" -> value = parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> row[cellIndex] = if (sharedString) shared.getOrElse(value.toIntOrNull() ?: -1) { "" } else value
                    "row" -> rows += List((if (row.isEmpty()) -1 else row.lastKey()) + 1) { row[it].orEmpty() }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter)
        return letters.fold(0) { total, char -> total * 26 + (char.uppercaseChar() - 'A' + 1) } - 1
    }

    private fun decode(bytes: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { java.nio.charset.Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString() }

    private fun parseTime(value: String): Long? {
        val cleaned = value.trim()
        val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm")
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching { LocalDateTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern)).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

object CsvTableParser {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { row += cell.toString(); cell.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
