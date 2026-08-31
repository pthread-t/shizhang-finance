package com.billrecord.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.billrecord.ledger.LedgerApplication
import com.billrecord.ledger.MainActivity
import com.billrecord.ledger.R
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.ui.components.formatMoney
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SummaryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val amount = runBlocking {
            val bookId = entryPoint.preferences().selectedBookId.first() ?: return@runBlocking 0L
            val (start, end) = entryPoint.repository().currentMonthRange()
            val summary = entryPoint.repository().observeSummary(bookId, start, end).first()
            summary.expenseMinor - summary.refundMinor
        }
        val intent = Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_SEND; type = "text/plain" }
        val pending = PendingIntent.getActivity(context, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_summary).apply {
                setTextViewText(R.id.widget_amount, formatMoney(amount))
                setOnClickPendingIntent(R.id.widget_action, pending)
                setOnClickPendingIntent(R.id.widget_amount, pending)
            }
            manager.updateAppWidget(id, views)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): LedgerRepository
    fun preferences(): AppPreferences
}

