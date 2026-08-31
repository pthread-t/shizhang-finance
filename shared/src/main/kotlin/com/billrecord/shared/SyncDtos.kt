package com.billrecord.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class BootstrapRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val inviteCode: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RecoverAccountRequest(
    val username: String,
    val recoveryCode: String,
    val newPassword: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String,
)

@Serializable
data class AuthResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochSeconds: Long,
    val recoveryCode: String? = null,
)

@Serializable
data class AccountProfileDto(
    val userId: String,
    val username: String,
)

@Serializable
data class UpdateCredentialsRequest(
    val currentPassword: String,
    val newUsername: String? = null,
    val newPassword: String? = null,
)

@Serializable
data class CredentialsUpdateResponse(
    val profile: AccountProfileDto,
    val session: AuthResponse,
)

@Serializable
data class CreateInviteRequest(
    val bookId: String,
    val role: BookRole,
    val expiresInHours: Int = 24,
)

@Serializable
data class InviteResponse(
    val code: String,
    val bookId: String,
    val role: BookRole,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class AcceptInviteRequest(val code: String)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val lastSeenAtEpochMillis: Long,
    val current: Boolean,
)

@Serializable
data class MemberDto(
    val userId: String,
    val username: String,
    val role: BookRole,
)

@Serializable
data class MyMembershipDto(
    val bookId: String,
    val role: BookRole,
)

@Serializable
data class AuditEventDto(
    val id: String,
    val bookId: String,
    val actorId: String,
    val actorName: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val action: String,
    val changedFields: Set<String> = emptySet(),
    val occurredAtEpochMillis: Long,
)

@Serializable
data class UpdateMemberRoleRequest(val role: BookRole)

@Serializable
data class AttachmentUploadResponse(
    val id: String,
    val remoteKey: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class InitAttachmentUploadRequest(
    val attachmentId: String,
    val bookId: String,
    val transactionId: String,
    val displayName: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class AttachmentUploadSession(
    val uploadId: String,
    val attachmentId: String,
    val offset: Long,
    val chunkSize: Int,
    val completed: Boolean,
    val result: AttachmentUploadResponse? = null,
)

@Serializable
data class SyncOperation(
    val operationId: String,
    val bookId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperationType,
    val baseVersion: Long,
    val changedFields: Set<String>,
    val payload: JsonObject,
    val clientModifiedAt: Long,
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val cursorByBook: Map<String, Long>,
    val operations: List<SyncOperation>,
)

@Serializable
data class RemoteChange(
    val bookId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val version: Long,
    val sequence: Long,
    val deleted: Boolean,
    val payload: JsonObject,
    val serverModifiedAt: Long,
)

@Serializable
data class SyncConflict(
    val operationId: String,
    val bookId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val conflictingFields: Set<String>,
    val localPayload: JsonObject,
    val serverPayload: JsonObject,
    val serverVersion: Long,
    val localOperation: SyncOperationType = SyncOperationType.UPSERT,
    val serverDeleted: Boolean = false,
)

@Serializable
data class SyncResponse(
    val acknowledgedOperationIds: List<String>,
    val cursorByBook: Map<String, Long>,
    val changes: List<RemoteChange>,
    val conflicts: List<SyncConflict>,
)

@Serializable
data class BookChangedMessage(
    val bookId: String,
    val sequence: Long,
)
