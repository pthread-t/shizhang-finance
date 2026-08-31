package com.billrecord.ledger.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ai_provider_profiles")
data class AiProviderProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val providerKind: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "ai_conversations", indices = [Index("bookId"), Index("updatedAt")])
data class AiConversationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "ai_messages", indices = [Index("conversationId"), Index("createdAt")])
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val status: String,
    val providerProfileId: String? = null,
    val model: String? = null,
    val queryPlanJson: String? = null,
    val aggregateJson: String? = null,
    val chartJson: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
)
