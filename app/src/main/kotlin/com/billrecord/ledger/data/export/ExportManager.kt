package com.billrecord.ledger.data.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.TransactionSort
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.TransactionEntity
import com.billrecord.shared.TransactionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat { CSV, XLSX }

class ExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class ExportManager @Inject constructor(
    private val repository: LedgerRepository,
    private val database: AppDatabase,
) {
    suspend fun export(filter: TransactionFilter, format: ExportFormat, output: OutputStream) =
        export(filter, TransactionSort.DATE_DESC, format, output)

    suspend fun export(filter: TransactionFilter, sort: TransactionSort, format: ExportFormat, output: OutputStream) {
        try {
            withContext(Dispatchers.IO) {
                database.withTransaction { exportSnapshot(filter, sort, format, output) }
            }
        } catch (error: ExportException) {
            throw error
        } catch (error: IOException) {
            throw ExportException(EXPORT_IO_ERROR, error)
        }
    }

    suspend fun exportToUri(context: Context, uri: Uri, filter: TransactionFilter, sort: TransactionSort, format: ExportFormat) {
        val resolver = context.contentResolver
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw ExportException("无法打开导出位置，请选择其他目录")
            output.buffered().use { export(filter, sort, format, it) }
        } catch (error: Throwable) {
            // CreateDocument can leave a truncated or corrupt file after an I/O failure.
            // Deletion is best-effort because not every document provider supports it.
            runCatching { resolver.delete(uri, null, null) }
            when (error) {
                is ExportException -> throw error
                is IOException -> throw ExportException(EXPORT_IO_ERROR, error)
                else -> throw error
            }
        }
    }

    suspend fun exportToUri(context: Context, uri: Uri, filter: TransactionFilter, format: ExportFormat) =
        exportToUri(context, uri, filter, TransactionSort.DATE_DESC, format)

    private suspend fun exportSnapshot(filter: TransactionFilter, sort: TransactionSort, format: ExportFormat, output: OutputStream) {
        val books = repository.getBooks().filter { filter.bookIds.isEmpty() || it.id in filter.bookIds }.associateBy { it.id }
        require(books.isNotEmpty()) { "没有可导出的账本" }
        val accounts = books.keys.flatMap { repository.getAccounts(it) }.associateBy { it.id }
        val categories = books.keys.flatMap { repository.getCategories(it) }.associateBy { it.id }
        val merchants = books.keys.flatMap { repository.getMerchants(it) }.associateBy { it.id }
        val projects = books.keys.flatMap { repository.getProjects(it) }.associateBy { it.id }
        val tags = books.keys.flatMap { repository.getTags(it) }.associateBy { it.id }
        val members = books.keys.flatMap { repository.getMemberships(it) }.associateBy { it.bookId to it.userId }
        val budgets = books.keys.flatMap { repository.getBudgets(it) }

        val loadRows: suspend (Int) -> List<List<Any?>> = { offset ->
            val transactions = repository.queryForExportPage(filter, sort, EXPORT_BATCH_SIZE, offset)
            val tagSummaries = repository.getTransactionTagSummaries(transactions.map { it.id }).associateBy { it.transactionId }
            val splits = repository.getTransactionSplits(transactions.map { it.id }).groupBy { it.transactionId }
            transactions.map { transaction ->
                val category = categories[transaction.categoryId]
                val parentCategory = category?.parentId?.let(categories::get)
                val tagSummary = tagSummaries[transaction.id]
                listOf(
                books[transaction.bookId]?.name.orEmpty(),
                transaction.bookId,
                transaction.id,
                formatTime(transaction.occurredAt),
                typeLabel(transaction),
                java.math.BigDecimal.valueOf(transaction.amountMinor, 2),
                transaction.currency,
                java.math.BigDecimal.valueOf(transaction.baseAmountMinor, 2),
                transaction.exchangeRate,
                parentCategory?.name ?: category?.name.orEmpty(),
                if (parentCategory == null) "" else category?.name.orEmpty(),
                transaction.categoryId.orEmpty(),
                splits[transaction.id].orEmpty().joinToString("|") { split ->
                    "${categories[split.categoryId]?.name ?: "未分类"}=${java.math.BigDecimal.valueOf(split.amountMinor, 2).toPlainString()}"
                },
                accounts[transaction.accountId]?.name.orEmpty(),
                transaction.accountId,
                accounts[transaction.destinationAccountId]?.name.orEmpty(),
                transaction.destinationAccountId.orEmpty(),
                transaction.destinationAmountMinor?.let { java.math.BigDecimal.valueOf(it, 2) },
                transaction.destinationCurrency.orEmpty(),
                tagSummary?.tagNames.orEmpty(),
                tagSummary?.tagIds.orEmpty(),
                merchants[transaction.merchantId]?.name.orEmpty(),
                transaction.merchantId.orEmpty(),
                projects[transaction.projectId]?.name.orEmpty(),
                transaction.projectId.orEmpty(),
                transaction.memberId?.let { members[transaction.bookId to it]?.displayName }.orEmpty(),
                transaction.memberId.orEmpty(),
                transaction.reimbursementStatus.name,
                transaction.refundOfTransactionId.orEmpty(),
                transaction.note,
                formatTime(transaction.createdAt),
                formatTime(transaction.updatedAt),
                )
            }
        }
        when (format) {
            ExportFormat.CSV -> writeCsv(output, loadRows)
            ExportFormat.XLSX -> SimpleXlsxWriter.write(
                output,
                HEADERS,
                loadRows,
                listOf(
                    XlsxSheet("账户", listOf("账本", "账户ID", "名称", "类型", "币种", "期初余额", "信用额度", "账单日", "还款日"), accounts.values.map { listOf(books[it.bookId]?.name.orEmpty(), it.id, it.name, it.type.name, it.currency, java.math.BigDecimal.valueOf(it.openingBalanceMinor, 2), it.creditLimitMinor?.let { value -> java.math.BigDecimal.valueOf(value, 2) }, it.statementDay, it.repaymentDay) }),
                    XlsxSheet("分类", listOf("账本", "分类ID", "父分类ID", "名称", "收支类型"), categories.values.map { listOf(books[it.bookId]?.name.orEmpty(), it.id, it.parentId, it.name, it.type.name) }),
                    XlsxSheet("标签", listOf("账本", "标签ID", "名称"), tags.values.map { listOf(books[it.bookId]?.name.orEmpty(), it.id, it.name) }),
                    XlsxSheet("成员", listOf("账本", "成员ID", "显示名", "角色"), members.values.map { listOf(books[it.bookId]?.name.orEmpty(), it.userId, it.displayName, it.role.name) }),
                    XlsxSheet("预算", listOf("账本", "预算ID", "名称", "分类ID", "周期", "金额", "币种", "滚存"), budgets.map { listOf(books[it.bookId]?.name.orEmpty(), it.id, it.name, it.categoryId, it.period, java.math.BigDecimal.valueOf(it.amountMinor, 2), it.currency, it.rollover) }),
                ),
            )
        }
    }

    private suspend fun writeCsv(output: OutputStream, loadRows: suspend (Int) -> List<List<Any?>>) {
        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.write(HEADERS.joinToString(",", transform = ::csv)); writer.write("\r\n")
            var offset = 0
            do {
                val rows = loadRows(offset)
                rows.forEach { row -> writer.write(row.joinToString(",", transform = ::csv)); writer.write("\r\n") }
                offset += rows.size
            } while (rows.size == EXPORT_BATCH_SIZE)
        }
    }

    private fun csv(value: Any?): String {
        val text = value?.toString().orEmpty()
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    private fun typeLabel(value: TransactionEntity) = when (value.type) {
        com.billrecord.shared.TransactionType.EXPENSE -> "支出"
        com.billrecord.shared.TransactionType.INCOME -> "收入"
        com.billrecord.shared.TransactionType.TRANSFER -> "转账"
        com.billrecord.shared.TransactionType.REFUND -> "退款"
        com.billrecord.shared.TransactionType.ADJUSTMENT -> "余额调整"
    }

    private fun formatTime(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Shanghai")).format(TIME_FORMAT)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val HEADERS = listOf(
            "账本", "账本ID", "账单ID", "时间", "类型", "金额", "币种", "本位币金额", "汇率", "一级分类", "二级分类", "分类ID", "拆分明细",
            "账户", "账户ID", "对方账户", "对方账户ID", "对方金额", "对方币种", "标签", "标签ID", "商家/对象", "商家ID",
            "项目", "项目ID", "成员", "成员ID", "报销状态", "退款关联", "备注", "创建时间", "更新时间",
        )
        const val EXPORT_BATCH_SIZE = 500
        const val EXPORT_IO_ERROR = "导出失败：存储空间不足或目标位置不可写，请释放空间或选择其他目录后重试"
    }
}

private data class XlsxSheet(val name: String, val headers: List<String>, val rows: List<List<Any?>>)

private object SimpleXlsxWriter {
    suspend fun write(
        output: OutputStream,
        headers: List<String>,
        loadRows: suspend (Int) -> List<List<Any?>>,
        extraSheets: List<XlsxSheet>,
    ) {
        val allNames = listOf("账单") + extraSheets.map { it.name }
        ZipOutputStream(output).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes(allNames.size))
            entry(zip, "_rels/.rels", rootRelationships())
            entry(zip, "xl/workbook.xml", workbook(allNames))
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(allNames.size))
            entry(zip, "xl/styles.xml", styles())
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            writeRow(writer, 1, headers)
            var offset = 0
            var rowNumber = 2
            do {
                val rows = loadRows(offset)
                rows.forEach { row -> writeRow(writer, rowNumber++, row) }
                offset += rows.size
            } while (rows.isNotEmpty())
            writer.write("</sheetData></worksheet>")
            writer.flush()
            zip.closeEntry()
            extraSheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 2}.xml"))
                val sheetWriter = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                sheetWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                writeRow(sheetWriter, 1, sheet.headers)
                sheet.rows.forEachIndexed { rowIndex, row -> writeRow(sheetWriter, rowIndex + 2, row) }
                sheetWriter.write("</sheetData></worksheet>")
                sheetWriter.flush()
                zip.closeEntry()
            }
        }
    }

    private fun writeRow(writer: BufferedWriter, rowNumber: Int, values: List<Any?>) {
        writer.write("<row r=\"$rowNumber\">")
        values.forEachIndexed { column, value ->
            val reference = "${columnName(column)}$rowNumber"
            when (value) {
                is Number -> writer.write("<c r=\"$reference\" s=\"1\"><v>${value}</v></c>")
                else -> writer.write("<c r=\"$reference\" t=\"inlineStr\"><is><t>${xml(value?.toString().orEmpty())}</t></is></c>")
            }
        }
        writer.write("</row>")
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) { value--; result.append(('A'.code + value % 26).toChar()); value /= 26 }
        return result.reverse().toString()
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun contentTypes(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        (1..sheetCount).forEach { append("<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
    }
    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbook(names: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { index, name -> append("<sheet name=\"${xml(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>") }
        append("</sheets></workbook>")
    }
    private fun workbookRelationships(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        (1..sheetCount).forEach { append("<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>") }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
    }
    private fun styles() = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="0.00"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf xfId="0"/><xf numFmtId="164" applyNumberFormat="1" xfId="0"/></cellXfs></styleSheet>"""
}
