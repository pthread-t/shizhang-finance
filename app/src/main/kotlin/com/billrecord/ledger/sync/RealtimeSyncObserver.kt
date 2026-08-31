package com.billrecord.ledger.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.billrecord.ledger.data.AppPreferences
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeSyncObserver @Inject constructor(
    private val client: HttpClient,
    private val preferences: AppPreferences,
    private val scheduler: SyncScheduler,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        scheduler.syncNow()
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val token = preferences.accessToken()
                if (token == null) { delay(15_000); continue }
                val base = preferences.serverUrl.first().trimEnd('/')
                val webSocketUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/v1/events"
                runCatching {
                    client.webSocket(urlString = webSocketUrl, request = { bearerAuth(token) }) {
                        for (frame in incoming) if (frame is Frame.Text) scheduler.syncNow()
                    }
                }
                delay(5_000)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }
}

