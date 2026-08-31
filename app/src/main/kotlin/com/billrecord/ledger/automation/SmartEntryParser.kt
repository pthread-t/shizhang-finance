package com.billrecord.ledger.automation

import com.billrecord.shared.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode

data class ParsedEntry(
    val amountMinor: Long?,
    val type: TransactionType,
    val categoryHint: String?,
    val accountHint: String?,
    val note: String,
    val confidence: Float,
)

object SmartEntryParser {
    private val amountPattern = Regex("(?<![\\d.])([0-9]{1,9}(?:\\.[0-9]{1,2})?)(?![\\d.])")
    private val categoryKeywords = linkedMapOf(
        "餐饮" to listOf("早餐", "午饭", "午餐", "晚饭", "晚餐", "咖啡", "奶茶", "外卖", "餐厅", "饭"),
        "交通" to listOf("地铁", "公交", "打车", "滴滴", "高铁", "机票", "加油", "停车"),
        "购物" to listOf("淘宝", "京东", "买", "超市", "衣服"),
        "居住" to listOf("房租", "物业", "水费", "电费", "燃气"),
        "医疗" to listOf("医院", "药", "挂号", "体检"),
        "娱乐" to listOf("电影", "游戏", "演出", "会员"),
        "工资" to listOf("工资", "薪水", "薪资"),
        "奖金" to listOf("奖金", "年终奖"),
    )
    private val accountKeywords = linkedMapOf(
        "微信" to listOf("微信", "零钱"),
        "支付宝" to listOf("支付宝", "花呗"),
        "银行卡" to listOf("银行卡", "储蓄卡", "银联"),
        "信用卡" to listOf("信用卡"),
        "现金" to listOf("现金"),
    )

    fun parse(raw: String): ParsedEntry {
        val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val amountMatch = amountPattern.findAll(text).lastOrNull()
        val amountMinor = amountMatch?.groupValues?.get(1)?.let {
            BigDecimal(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }
        val category = categoryKeywords.entries.firstOrNull { (_, words) -> words.any(text::contains) }?.key
        val account = accountKeywords.entries.firstOrNull { (_, words) -> words.any(text::contains) }?.key
        val type = if (text.contains("收入") || category in setOf("工资", "奖金")) TransactionType.INCOME else TransactionType.EXPENSE
        val note = amountMatch?.let { (text.removeRange(it.range)).trim(' ', '，', ',', '元') } ?: text
        val signals = listOf(amountMinor != null, category != null, account != null).count { it }
        return ParsedEntry(amountMinor, type, category, account, note, signals / 3f)
    }
}

