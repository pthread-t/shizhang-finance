package com.billrecord.server

data class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtIssuer: String,
    val attachmentPath: String,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/bill_record",
            databaseUser = env["DATABASE_USER"] ?: "bill_record",
            databasePassword = requireNotNull(env["DATABASE_PASSWORD"]) { "DATABASE_PASSWORD is required" },
            jwtSecret = requireNotNull(env["JWT_SECRET"]) { "JWT_SECRET is required" }.also { require(it.length >= 32) },
            jwtIssuer = env["JWT_ISSUER"] ?: "bill-record",
            attachmentPath = env["ATTACHMENT_PATH"] ?: "/data/attachments",
        )
    }
}

