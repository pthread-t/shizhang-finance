package com.billrecord.ledger.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.billrecord.ledger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("ledger_settings")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val masterKey by lazy {
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }
    private val secrets by lazy {
        EncryptedSharedPreferences.create(
            context,
            "ledger_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val _sessionUserId by lazy { MutableStateFlow(secrets.getString("user_id", null)) }
    val sessionUserId: StateFlow<String?> get() = _sessionUserId.asStateFlow()

    val selectedBookId: Flow<String?> = context.dataStore.data.map { it[BOOK_ID] }
    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL }
    val cloudBoundUserId: Flow<String?> = context.dataStore.data.map { it[CLOUD_BOUND_USER_ID] }
    val automationEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTOMATION_ENABLED] ?: false }
    val secureScreenEnabled: Flow<Boolean> = context.dataStore.data.map { it[SECURE_SCREEN] ?: false }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[APP_LOCK] ?: false }
    val dailyReminderEnabled: Flow<Boolean> = context.dataStore.data.map { it[DAILY_REMINDER] ?: false }
    val themeAccent: Flow<String> = context.dataStore.data.map { it[THEME_ACCENT] ?: "JADE" }
    val speechConsentAccepted: Flow<Boolean> = context.dataStore.data.map { it[SPEECH_CONSENT_ACCEPTED] ?: false }
    val transactionSort: Flow<String> = context.dataStore.data.map { it[TRANSACTION_SORT] ?: TransactionSort.DATE_DESC.name }

    suspend fun selectBook(id: String) = context.dataStore.edit { it[BOOK_ID] = id }
    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[SERVER_URL] = url.trimEnd('/') }
    suspend fun bindCloudUser(userId: String): Boolean {
        var accepted = false
        context.dataStore.edit { values ->
            val existing = values[CLOUD_BOUND_USER_ID]
            accepted = existing == null || existing == userId
            if (existing == null) values[CLOUD_BOUND_USER_ID] = userId
        }
        return accepted
    }
    suspend fun setAutomationEnabled(enabled: Boolean) = context.dataStore.edit { it[AUTOMATION_ENABLED] = enabled }
    suspend fun setSecureScreen(enabled: Boolean) = context.dataStore.edit { it[SECURE_SCREEN] = enabled }
    suspend fun setAppLock(enabled: Boolean) = context.dataStore.edit { it[APP_LOCK] = enabled }
    suspend fun setDailyReminder(enabled: Boolean) = context.dataStore.edit { it[DAILY_REMINDER] = enabled }
    suspend fun setThemeAccent(value: String) = context.dataStore.edit { it[THEME_ACCENT] = value }
    suspend fun acceptSpeechConsent() = context.dataStore.edit { it[SPEECH_CONSENT_ACCEPTED] = true }
    fun exchangeRate(bookId: String, currency: String): Flow<String?> = context.dataStore.data.map {
        it[stringPreferencesKey("exchange_rate_${bookId}_${currency.uppercase()}")]
    }
    suspend fun setExchangeRate(bookId: String, currency: String, value: String) = context.dataStore.edit {
        it[stringPreferencesKey("exchange_rate_${bookId}_${currency.uppercase()}")] = value
    }
    suspend fun setTransactionSort(value: TransactionSort) = context.dataStore.edit { it[TRANSACTION_SORT] = value.name }

    fun accessToken(): String? = secrets.getString("access_token", null)
    fun refreshToken(): String? = secrets.getString("refresh_token", null)
    fun userId(): String? = secrets.getString("user_id", null)
    fun username(): String? = secrets.getString("username", null)
    fun deviceId(): String = secrets.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
        secrets.edit().putString("device_id", it).apply()
    }

    fun saveSession(userId: String, accessToken: String, refreshToken: String) {
        secrets.edit()
            .putString("user_id", userId)
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
        _sessionUserId.value = userId
    }

    fun saveUsername(username: String) {
        secrets.edit().putString("username", username).apply()
    }

    fun clearSession() {
        secrets.edit().remove("user_id").remove("access_token").remove("refresh_token").remove("username").apply()
        _sessionUserId.value = null
    }

    fun aiApiKey(profileId: String): String? = secrets.getString("ai_api_key_$profileId", null)

    fun saveAiApiKey(profileId: String, apiKey: String) {
        secrets.edit().putString("ai_api_key_$profileId", apiKey.trim()).apply()
    }

    fun deleteAiApiKey(profileId: String) {
        secrets.edit().remove("ai_api_key_$profileId").apply()
    }

    private companion object {
        val BOOK_ID = stringPreferencesKey("selected_book_id")
        val SERVER_URL = stringPreferencesKey("server_url")
        val CLOUD_BOUND_USER_ID = stringPreferencesKey("cloud_bound_user_id")
        val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
        val THEME_ACCENT = stringPreferencesKey("theme_accent")
        val SPEECH_CONSENT_ACCEPTED = booleanPreferencesKey("speech_consent_accepted")
        val TRANSACTION_SORT = stringPreferencesKey("transaction_sort")
    }
}
