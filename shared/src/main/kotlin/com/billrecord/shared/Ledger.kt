package com.billrecord.shared

import kotlinx.serialization.Serializable

@Serializable
data class LedgerDraft(
    val transactionId: String,
    val type: TransactionType,
    val accountId: String,
    val destinationAccountId: String? = null,
    val categoryId: String? = null,
    val amountMinor: Long,
    val currency: String = "CNY",
    val baseAmountMinor: Long = amountMinor,
    val destinationAmountMinor: Long? = null,
    val destinationCurrency: String? = null,
    val equityAccountId: String = SYSTEM_EQUITY_ACCOUNT,
    val splits: List<LedgerSplitDraft> = emptyList(),
)

@Serializable
data class LedgerSplitDraft(
    val categoryId: String? = null,
    val amountMinor: Long,
    val baseAmountMinor: Long = amountMinor,
)

@Serializable
data class PostingDraft(
    val transactionId: String,
    val ledgerAccountId: String,
    val amountMinor: Long,
    val currency: String,
    val baseAmountMinor: Long,
)

const val SYSTEM_EQUITY_ACCOUNT = "system:equity"

object LedgerEngine {
    fun createPostings(draft: LedgerDraft): List<PostingDraft> {
        require(draft.amountMinor > 0) { "amountMinor must be positive" }
        require(draft.baseAmountMinor > 0 || draft.type == TransactionType.ADJUSTMENT) {
            "baseAmountMinor must be positive"
        }

        if (draft.splits.isNotEmpty()) return createSplitPostings(draft)

        val postings = when (draft.type) {
            TransactionType.EXPENSE -> listOf(
                posting(draft, draft.accountId, -draft.amountMinor, -draft.baseAmountMinor),
                posting(draft, expenseAccount(draft.categoryId), draft.amountMinor, draft.baseAmountMinor),
            )
            TransactionType.INCOME -> listOf(
                posting(draft, draft.accountId, draft.amountMinor, draft.baseAmountMinor),
                posting(draft, incomeAccount(draft.categoryId), -draft.amountMinor, -draft.baseAmountMinor),
            )
            TransactionType.TRANSFER -> {
                val destinationId = requireNotNull(draft.destinationAccountId) { "destination account is required" }
                require(destinationId != draft.accountId) { "transfer accounts must differ" }
                listOf(
                    posting(draft, draft.accountId, -draft.amountMinor, -draft.baseAmountMinor),
                    PostingDraft(
                        transactionId = draft.transactionId,
                        ledgerAccountId = destinationId,
                        amountMinor = draft.destinationAmountMinor ?: draft.amountMinor,
                        currency = draft.destinationCurrency ?: draft.currency,
                        baseAmountMinor = draft.baseAmountMinor,
                    ),
                )
            }
            TransactionType.REFUND -> listOf(
                posting(draft, draft.accountId, draft.amountMinor, draft.baseAmountMinor),
                posting(draft, expenseAccount(draft.categoryId), -draft.amountMinor, -draft.baseAmountMinor),
            )
            TransactionType.ADJUSTMENT -> listOf(
                posting(draft, draft.accountId, draft.amountMinor, draft.baseAmountMinor),
                posting(draft, draft.equityAccountId, -draft.amountMinor, -draft.baseAmountMinor),
            )
        }

        validate(postings)
        return postings
    }

    private fun createSplitPostings(draft: LedgerDraft): List<PostingDraft> {
        require(draft.type in setOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.REFUND)) {
            "only expense, income and refund transactions can be split"
        }
        require(draft.splits.all { it.amountMinor > 0 && it.baseAmountMinor > 0 }) { "split amounts must be positive" }
        require(draft.splits.sumOf { it.amountMinor } == draft.amountMinor) { "split amounts must equal transaction amount" }
        require(draft.splits.sumOf { it.baseAmountMinor } == draft.baseAmountMinor) { "split base amounts must equal transaction base amount" }
        val accountSign = if (draft.type == TransactionType.EXPENSE) -1 else 1
        val categorySign = -accountSign
        val postings = buildList {
            add(posting(draft, draft.accountId, accountSign * draft.amountMinor, accountSign * draft.baseAmountMinor))
            draft.splits.forEach { split ->
                add(
                    PostingDraft(
                        transactionId = draft.transactionId,
                        ledgerAccountId = if (draft.type == TransactionType.INCOME) incomeAccount(split.categoryId) else expenseAccount(split.categoryId),
                        amountMinor = categorySign * split.amountMinor,
                        currency = draft.currency,
                        baseAmountMinor = categorySign * split.baseAmountMinor,
                    ),
                )
            }
        }
        validate(postings)
        return postings
    }

    fun validate(postings: List<PostingDraft>) {
        require(postings.size >= 2) { "a transaction requires at least two postings" }
        require(postings.map { it.transactionId }.distinct().size == 1) { "postings must share a transaction" }
        require(postings.sumOf { it.baseAmountMinor } == 0L) { "postings are not balanced in base currency" }
    }

    private fun posting(draft: LedgerDraft, accountId: String, amount: Long, base: Long) = PostingDraft(
        transactionId = draft.transactionId,
        ledgerAccountId = accountId,
        amountMinor = amount,
        currency = draft.currency,
        baseAmountMinor = base,
    )

    private fun expenseAccount(categoryId: String?) = "system:expense:${categoryId ?: "uncategorized"}"
    private fun incomeAccount(categoryId: String?) = "system:income:${categoryId ?: "uncategorized"}"
}
