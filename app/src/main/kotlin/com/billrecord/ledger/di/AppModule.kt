package com.billrecord.ledger.di

import android.content.Context
import androidx.room.Room
import com.billrecord.ledger.BuildConfig
import com.billrecord.ledger.data.local.AppDatabase
import com.billrecord.ledger.data.local.LedgerDao
import com.billrecord.ledger.data.local.MIGRATION_1_2
import com.billrecord.ledger.data.local.MIGRATION_2_3
import com.billrecord.ledger.security.DatabasePassphrase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(DatabasePassphrase(context).getOrCreate())
        return Room.databaseBuilder(context, AppDatabase::class.java, "ledger.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides fun ledgerDao(database: AppDatabase): LedgerDao = database.ledgerDao()

    @Provides
    @Singleton
    fun httpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 90_000
        }
        install(WebSockets)
        defaultRequest {
            url(BuildConfig.DEFAULT_SERVER_URL)
            contentType(ContentType.Application.Json)
        }
    }
}
