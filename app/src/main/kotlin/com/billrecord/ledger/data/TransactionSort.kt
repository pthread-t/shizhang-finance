package com.billrecord.ledger.data

enum class TransactionSort(val label: String) {
    DATE_DESC("日期：最新优先"),
    DATE_ASC("日期：最早优先"),
    AMOUNT_DESC("金额：从高到低"),
    AMOUNT_ASC("金额：从低到高"),
    TITLE_ASC("名称：正序"),
    TITLE_DESC("名称：倒序"),
}

internal fun transactionOrderBy(sort: TransactionSort): String = when (sort) {
    TransactionSort.DATE_DESC -> "occurredAt DESC, id DESC"
    TransactionSort.DATE_ASC -> "occurredAt ASC, id ASC"
    TransactionSort.AMOUNT_DESC -> "ABS(baseAmountMinor) DESC, occurredAt DESC, id DESC"
    TransactionSort.AMOUNT_ASC -> "ABS(baseAmountMinor) ASC, occurredAt DESC, id DESC"
    TransactionSort.TITLE_ASC -> "CASE WHEN TRIM(note) != '' THEN note ELSE COALESCE((SELECT name FROM categories WHERE id = transactions.categoryId), type) END COLLATE LOCALIZED ASC, occurredAt DESC, id DESC"
    TransactionSort.TITLE_DESC -> "CASE WHEN TRIM(note) != '' THEN note ELSE COALESCE((SELECT name FROM categories WHERE id = transactions.categoryId), type) END COLLATE LOCALIZED DESC, occurredAt DESC, id DESC"
}
