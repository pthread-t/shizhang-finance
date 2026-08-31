package com.billrecord.ledger

import android.os.Bundle
import android.view.WindowManager
import com.billrecord.ledger.BuildConfig
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.data.AppPreferences
import com.billrecord.ledger.ui.AppRoot
import com.billrecord.ledger.ui.AppViewModel
import com.billrecord.ledger.ui.CloudGateScreen
import com.billrecord.ledger.ui.theme.LedgerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var preferences: AppPreferences
    private val unlocked = mutableStateOf(true)
    private var appLockEnabled = false
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(DEBUG_SERVER_URL_EXTRA)
                ?.takeIf(::isAllowedDebugServerUrl)
                ?.let { runBlocking { preferences.setServerUrl(it) } }
        }
        val secure = runBlocking { preferences.secureScreenEnabled.first() }
        appLockEnabled = runBlocking { preferences.appLockEnabled.first() }
        unlocked.value = !appLockEnabled
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val accent by preferences.themeAccent.collectAsStateWithLifecycle(initialValue = "JADE")
            LedgerTheme(accent = accent) {
                val darkTheme = isSystemInDarkTheme()
                val systemBarColor = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    window.statusBarColor = systemBarColor
                    window.navigationBarColor = systemBarColor
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                if (unlocked.value) {
                    val viewModel: AppViewModel = hiltViewModel()
                    val launchState by viewModel.launchState.collectAsStateWithLifecycle()
                    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
                    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { viewModel.initialize() }
                    if (BuildConfig.CLOUD_FIRST && !launchState.ready) {
                        CloudGateScreen(launchState, viewModel::login, viewModel::retryCloudSync)
                    } else {
                        AppRoot(
                            ready = launchState.ready,
                            canEdit = canEdit,
                            sharedIntent = intent,
                            syncStatus = syncStatus,
                            onRetrySync = viewModel::retryBackgroundSync,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("拾账已锁定", style = MaterialTheme.typography.headlineSmall)
                        Text("账本内容在验证前不会显示")
                        Button(onClick = ::authenticate) { Text("解锁") }
                    }
                }
            }
        }
        if (appLockEnabled) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (appLockEnabled && !isChangingConfigurations) unlocked.value = false
    }

    override fun onResume() {
        super.onResume()
        if (appLockEnabled && !unlocked.value && !promptVisible) authenticate()
    }

    private fun authenticate() {
        if (promptVisible || !appLockEnabled) return
        promptVisible = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptVisible = false
                unlocked.value = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptVisible = false
            }
        })
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁拾账")
                .setSubtitle("验证身份以查看本地账本")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun isAllowedDebugServerUrl(value: String): Boolean =
        value == "http://127.0.0.1:18080" || value == BuildConfig.DEFAULT_SERVER_URL

    private companion object {
        const val DEBUG_SERVER_URL_EXTRA = "com.billrecord.ledger.debug.SERVER_URL"
    }
}
