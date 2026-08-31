package com.billrecord.ledger

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.billrecord.ledger.sync.SyncScheduler
import com.billrecord.ledger.sync.RealtimeSyncObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.billrecord.ledger.automation.MaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LedgerApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var realtimeSyncObserver: RealtimeSyncObserver
    @Inject lateinit var maintenanceScheduler: MaintenanceScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.schedulePeriodicSync()
        maintenanceScheduler.schedule()
        ProcessLifecycleOwner.get().lifecycle.addObserver(realtimeSyncObserver)
    }
}
