package com.billrecord.ledger.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.billrecord.ledger.MainActivity
import com.billrecord.ledger.R
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val preferences: AppPreferences,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!preferences.dailyReminderEnabled.first()) return Result.success()
        showNotification(applicationContext, "今天的账记完了吗？", "花半分钟补齐流水，月底的报表才准确。", 7001)
        return Result.success()
    }
}

@HiltWorker
class RecurringRuleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val count = repository.processDueRecurringRules()
        if (count > 0) showNotification(applicationContext, "周期账单已生成", "已按计划记录 $count 笔流水。", 7002)
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class BudgetAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bookId = repository.observeSelectedBookId().first() ?: return Result.success()
        val budgets = repository.observeBudgets(bookId).first()
        val usage = repository.observeBudgetUsage(bookId).first().associate { it.budgetId to it.usedMinor }
        budgets.filter { budget -> (usage[budget.id] ?: 0L) * 100 >= budget.amountMinor * budget.alertThresholdPercent }.forEach { budget ->
            val used = usage[budget.id] ?: 0L
            showNotification(applicationContext, "预算提醒", "${budget.name}已使用 ${used * 100 / budget.amountMinor}%", budget.id.hashCode())
        }
        return Result.success()
    }
}

@HiltWorker
class InstallmentReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val due = repository.dueInstallmentPlans()
        if (due.isNotEmpty()) {
            val title = if (due.size == 1) "分期到期：${due.first().name}" else "有 ${due.size} 个分期计划到期"
            showNotification(applicationContext, title, "请核对还款后在资产页标记本期已还。", 7003)
        }
        Result.success()
    }.getOrElse { Result.retry() }
}

@HiltWorker
class CreditCardReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LedgerRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val bookId = repository.observeSelectedBookId().first() ?: return Result.success()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val due = repository.observeAccounts(bookId).first().filter { account ->
            val configured = account.repaymentDay ?: return@filter false
            today.dayOfMonth == minOf(configured, today.lengthOfMonth())
        }
        if (due.isNotEmpty()) {
            showNotification(applicationContext, "信用卡还款提醒", due.joinToString("、") { it.name } + " 今天到还款日，请核对账单。", 7004)
        }
        Result.success()
    }.getOrElse { Result.retry() }
}

@Singleton
class MaintenanceScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() {
        val now = ZonedDateTime.now()
        var next = now.withHour(20).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val reminder = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-record-reminder", ExistingPeriodicWorkPolicy.UPDATE, reminder)

        val recurring = PeriodicWorkRequestBuilder<RecurringRuleWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("recurring-rules", ExistingPeriodicWorkPolicy.UPDATE, recurring)
        val budget = PeriodicWorkRequestBuilder<BudgetAlertWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("budget-alerts", ExistingPeriodicWorkPolicy.UPDATE, budget)
        val installments = PeriodicWorkRequestBuilder<InstallmentReminderWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("installment-reminders", ExistingPeriodicWorkPolicy.UPDATE, installments)
        val creditCards = PeriodicWorkRequestBuilder<CreditCardReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("credit-card-reminders", ExistingPeriodicWorkPolicy.UPDATE, creditCards)
    }
}

private fun showNotification(context: Context, title: String, text: String, id: Int) {
    if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("ledger-reminders", "记账与预算提醒", NotificationManager.IMPORTANCE_DEFAULT))
    val pending = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(id, NotificationCompat.Builder(context, "ledger-reminders").setSmallIcon(R.drawable.ic_app).setContentTitle(title).setContentText(text).setContentIntent(pending).setAutoCancel(true).build())
}
