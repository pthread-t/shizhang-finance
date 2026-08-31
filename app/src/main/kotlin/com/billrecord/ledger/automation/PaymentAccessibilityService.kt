package com.billrecord.ledger.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.billrecord.ledger.MainActivity
import com.billrecord.ledger.R
import com.billrecord.ledger.data.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

data class PaymentCandidate(val amount: String, val merchant: String?, val source: String, val confidence: Float)

object PaymentPageParser {
    private val amount = Regex("(?:¥|￥|金额[:：]?\\s*)([0-9]{1,9}(?:\\.[0-9]{1,2})?)")
    private val completionWords = listOf("支付成功", "付款成功", "交易成功", "已支付")
    fun parse(source: String, texts: List<String>): PaymentCandidate? {
        val normalized = texts.joinToString(" ").replace(Regex("\\s+"), " ")
        if (completionWords.none(normalized::contains)) return null
        val value = amount.find(normalized)?.groupValues?.get(1) ?: return null
        val merchant = texts.firstOrNull { text -> text.length in 2..40 && text.none(Char::isDigit) && completionWords.none(text::contains) }
        return PaymentCandidate(value, merchant, source, if (merchant == null) 0.72f else 0.9f)
    }
}

@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {
    @Inject lateinit var preferences: AppPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastFingerprint: String? = null
    private var lastCapturedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        createChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in ALLOWED_PACKAGES) return
        scope.launch {
            if (!preferences.automationEnabled.first()) return@launch
            val texts = mutableListOf<String>()
            collectText(rootInActiveWindow, texts, 0)
            val source = if (packageName == "com.tencent.mm") "微信" else "支付宝"
            val candidate = PaymentPageParser.parse(source, texts) ?: return@launch
            val fingerprint = sha256("${candidate.source}|${candidate.amount}|${candidate.merchant}")
            val now = System.currentTimeMillis()
            if (fingerprint == lastFingerprint && now - lastCapturedAt < 60_000) return@launch
            lastFingerprint = fingerprint
            lastCapturedAt = now
            showConfirmation(candidate)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun collectText(node: AccessibilityNodeInfo?, output: MutableList<String>, depth: Int) {
        if (node == null || depth > 12 || output.size > 150) return
        node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(output::add)
        for (index in 0 until node.childCount) collectText(node.getChild(index), output, depth + 1)
    }

    private fun showConfirmation(candidate: PaymentCandidate) {
        val entryText = listOfNotNull(candidate.source, candidate.merchant, candidate.amount).joinToString(" ")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, entryText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, entryText.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("发现一笔${candidate.source}付款")
            .setContentText("¥${candidate.amount}${candidate.merchant?.let { " · $it" }.orEmpty()}，点按确认入账")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(entryText.hashCode(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "支付识别确认", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "在本机识别支付完成页后，请你确认是否入账"
                },
            )
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CHANNEL_ID = "payment-capture"
        val ALLOWED_PACKAGES = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    }
}

