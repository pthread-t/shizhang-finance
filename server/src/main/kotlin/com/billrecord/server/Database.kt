package com.billrecord.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

class Database(config: AppConfig) : AutoCloseable {
    private val hikari = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 10
        minimumIdle = 1
        isAutoCommit = true
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        validate()
    })

    val dataSource: DataSource get() = hikari

    fun migrate() {
        Flyway.configure().dataSource(hikari).locations("classpath:db/migration").load().migrate()
    }

    fun <T> transaction(block: (Connection) -> T): T = hikari.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    override fun close() = hikari.close()
}

