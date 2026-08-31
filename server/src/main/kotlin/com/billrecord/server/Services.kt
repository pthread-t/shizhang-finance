package com.billrecord.server

import com.billrecord.shared.AcceptInviteRequest
import com.billrecord.shared.AuthResponse
import com.billrecord.shared.AuditEventDto
import com.billrecord.shared.BookRole
import com.billrecord.shared.BootstrapRequest
import com.billrecord.shared.CreateInviteRequest
import com.billrecord.shared.DeviceDto
import com.billrecord.shared.InviteResponse
import com.billrecord.shared.LoginRequest
import com.billrecord.shared.MemberDto
import com.billrecord.shared.MyMembershipDto
import com.billrecord.shared.RefreshRequest
import com.billrecord.shared.RegisterRequest
import com.billrecord.shared.RecoverAccountRequest
import com.billrecord.shared.SyncEntityType
import com.billrecord.shared.AccountProfileDto
import com.billrecord.shared.UpdateCredentialsRequest
import com.billrecord.shared.CredentialsUpdateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)

class AuthService(
    private val database: Database,
    private val passwords: PasswordService,
    private val tokens: TokenService,
) {
    fun profile(userId: UUID): AccountProfileDto = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT username FROM users WHERE id = ? AND disabled_at IS NULL").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ApiException(404, "user_not_found", "账号不存在")
                AccountProfileDto(userId.toString(), result.getString("username"))
            }
        }
    }

    fun updateCredentials(userId: UUID, currentDeviceId: UUID, request: UpdateCredentialsRequest): CredentialsUpdateResponse = database.transaction { connection ->
        val newUsername = request.newUsername?.trim()?.takeIf { it.isNotEmpty() }
        val newPassword = request.newPassword?.takeIf { it.isNotEmpty() }
        if (newUsername == null && newPassword == null) throw ApiException(400, "no_changes", "请填写新用户名或新密码")
        newUsername?.let(::validateUsername)
        newPassword?.let(::validatePassword)
        val current = connection.prepareStatement("SELECT username, password_hash FROM users WHERE id = ? AND disabled_at IS NULL FOR UPDATE").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { if (it.next()) it.getString("username") to it.getString("password_hash") else null }
        } ?: throw ApiException(404, "user_not_found", "账号不存在")
        if (!passwords.verify(current.second, request.currentPassword.toCharArray())) {
            throw ApiException(401, "invalid_current_password", "当前密码错误")
        }
        val normalizedUsername = newUsername?.lowercase() ?: current.first
        if (normalizedUsername != current.first) {
            val exists = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM users WHERE username = ? AND id <> ?)").use { statement ->
                statement.setString(1, normalizedUsername); statement.setObject(2, userId)
                statement.executeQuery().use { it.next(); it.getBoolean(1) }
            }
            if (exists) throw ApiException(409, "username_taken", "用户名已存在")
        }
        val passwordHash = newPassword?.let { passwords.hash(it.toCharArray()) }
        connection.prepareStatement(
            "UPDATE users SET username = ?, password_hash = COALESCE(?, password_hash), session_generation = session_generation + 1 WHERE id = ?",
        ).use { statement ->
            statement.setString(1, normalizedUsername); statement.setString(2, passwordHash); statement.setObject(3, userId); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setObject(1, userId); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE devices SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setObject(1, userId); statement.executeUpdate()
        }
        val session = issueSession(connection, userId, currentDeviceId, null)
        CredentialsUpdateResponse(AccountProfileDto(userId.toString(), normalizedUsername), session)
    }

    fun bootstrap(request: BootstrapRequest): AuthResponse = database.transaction { connection ->
        validateCredentials(request.username, request.password)
        connection.prepareStatement("SELECT pg_advisory_xact_lock(81422026)").use { it.execute() }
        val count = connection.prepareStatement("SELECT COUNT(*) FROM users").use { statement -> statement.executeQuery().use { it.next(); it.getInt(1) } }
        if (count > 0) throw ApiException(409, "already_bootstrapped", "服务器已经创建过首个账号")
        val userId = UUID.randomUUID()
        val recoveryCode = tokens.humanCode(16)
        connection.prepareStatement("INSERT INTO users(id, username, password_hash, recovery_hash) VALUES (?, ?, ?, ?)").use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, request.username.lowercase())
            statement.setString(3, passwords.hash(request.password.toCharArray()))
            statement.setString(4, tokens.sha256(recoveryCode))
            statement.executeUpdate()
        }
        val response = issueSession(connection, userId, UUID.fromString(request.deviceId), request.deviceName)
        response.copy(recoveryCode = recoveryCode)
    }

    fun login(request: LoginRequest): AuthResponse = database.transaction { connection ->
        validateCredentials(request.username, request.password)
        val user = connection.prepareStatement("SELECT id, password_hash FROM users WHERE username = ? AND disabled_at IS NULL").use { statement ->
            statement.setString(1, request.username.lowercase())
            statement.executeQuery().use { result -> if (result.next()) result.getObject("id", UUID::class.java) to result.getString("password_hash") else null }
        } ?: throw ApiException(401, "invalid_credentials", "用户名或密码错误")
        if (!passwords.verify(user.second, request.password.toCharArray())) throw ApiException(401, "invalid_credentials", "用户名或密码错误")
        issueSession(connection, user.first, UUID.fromString(request.deviceId), request.deviceName)
    }

    fun register(request: RegisterRequest): AuthResponse = database.transaction { connection ->
        validateCredentials(request.username, request.password)
        val normalizedCode = request.inviteCode.uppercase().trim()
        val invite = connection.prepareStatement(
            "SELECT book_id, role FROM invites WHERE code_hash = ? AND used_at IS NULL AND expires_at > now() FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tokens.sha256(normalizedCode))
            statement.executeQuery().use { if (it.next()) it.getObject("book_id", UUID::class.java) to BookRole.valueOf(it.getString("role")) else null }
        } ?: throw ApiException(404, "invalid_invite", "邀请码无效或已过期")
        val username = request.username.lowercase()
        val exists = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM users WHERE username = ?)").use { statement ->
            statement.setString(1, username); statement.executeQuery().use { it.next(); it.getBoolean(1) }
        }
        if (exists) throw ApiException(409, "username_taken", "用户名已存在，请直接登录")
        val userId = UUID.randomUUID()
        val recoveryCode = tokens.humanCode(16)
        connection.prepareStatement("INSERT INTO users(id, username, password_hash, recovery_hash) VALUES (?, ?, ?, ?)").use { statement ->
            statement.setObject(1, userId); statement.setString(2, username); statement.setString(3, passwords.hash(request.password.toCharArray())); statement.setString(4, tokens.sha256(recoveryCode)); statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO memberships(book_id, user_id, role) VALUES (?, ?, ?)").use { statement ->
            statement.setObject(1, invite.first); statement.setObject(2, userId); statement.setString(3, invite.second.name); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE invites SET used_by = ?, used_at = now() WHERE code_hash = ?").use { statement ->
            statement.setObject(1, userId); statement.setString(2, tokens.sha256(normalizedCode)); statement.executeUpdate()
        }
        issueSession(connection, userId, UUID.fromString(request.deviceId), request.deviceName).copy(recoveryCode = recoveryCode)
    }

    fun recover(request: RecoverAccountRequest): AuthResponse = database.transaction { connection ->
        validateCredentials(request.username, request.newPassword)
        val user = connection.prepareStatement("SELECT id, recovery_hash FROM users WHERE username = ? AND disabled_at IS NULL FOR UPDATE").use { statement ->
            statement.setString(1, request.username.lowercase())
            statement.executeQuery().use { if (it.next()) it.getObject("id", UUID::class.java) to it.getString("recovery_hash") else null }
        } ?: throw ApiException(401, "invalid_recovery", "用户名或恢复码错误")
        val supplied = tokens.sha256(request.recoveryCode.uppercase().trim()).toByteArray()
        if (!java.security.MessageDigest.isEqual(user.second.toByteArray(), supplied)) throw ApiException(401, "invalid_recovery", "用户名或恢复码错误")
        val nextRecoveryCode = tokens.humanCode(16)
        connection.prepareStatement("UPDATE users SET password_hash = ?, recovery_hash = ?, session_generation = session_generation + 1 WHERE id = ?").use { statement ->
            statement.setString(1, passwords.hash(request.newPassword.toCharArray())); statement.setString(2, tokens.sha256(nextRecoveryCode)); statement.setObject(3, user.first); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setObject(1, user.first); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE devices SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setObject(1, user.first); statement.executeUpdate()
        }
        issueSession(connection, user.first, UUID.fromString(request.deviceId), request.deviceName).copy(recoveryCode = nextRecoveryCode)
    }

    fun refresh(request: RefreshRequest): AuthResponse = database.transaction { connection ->
        val tokenHash = tokens.sha256(request.refreshToken)
        val deviceId = UUID.fromString(request.deviceId)
        val userId = connection.prepareStatement(
            "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND device_id = ? AND revoked_at IS NULL AND expires_at > now() FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tokenHash); statement.setObject(2, deviceId)
            statement.executeQuery().use { if (it.next()) it.getObject("user_id", UUID::class.java) else null }
        } ?: throw ApiException(401, "invalid_refresh_token", "登录已失效，请重新登录")
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ?").use { it.setString(1, tokenHash); it.executeUpdate() }
        issueSession(connection, userId, deviceId, null)
    }

    fun listDevices(userId: UUID, currentDeviceId: UUID): List<DeviceDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id, name, last_seen_at FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(DeviceDto(result.getObject("id").toString(), result.getString("name"), result.getTimestamp("last_seen_at").time, result.getObject("id") == currentDeviceId)) }
            }
        }
    }

    fun revokeDevice(userId: UUID, deviceId: UUID) = database.transaction { connection ->
        connection.prepareStatement("UPDATE devices SET revoked_at = now() WHERE user_id = ? AND id = ?").use { statement ->
            statement.setObject(1, userId); statement.setObject(2, deviceId)
            if (statement.executeUpdate() == 0) throw ApiException(404, "device_not_found", "设备不存在")
        }
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setObject(1, userId); statement.setObject(2, deviceId); statement.executeUpdate()
        }
    }

    fun isDeviceActive(userId: UUID, deviceId: UUID, sessionGeneration: Long): Boolean = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT EXISTS(
                   SELECT 1 FROM devices d JOIN users u ON u.id = d.user_id
                   WHERE d.user_id = ? AND d.id = ? AND d.revoked_at IS NULL AND u.session_generation = ?
               )""",
        ).use { statement ->
            statement.setObject(1, userId); statement.setObject(2, deviceId); statement.setLong(3, sessionGeneration)
            statement.executeQuery().use { it.next(); it.getBoolean(1) }
        }
    }

    private fun issueSession(connection: Connection, userId: UUID, deviceId: UUID, deviceName: String?): AuthResponse {
        connection.prepareStatement(
            """INSERT INTO devices(id, user_id, name, last_seen_at, revoked_at) VALUES (?, ?, ?, now(), NULL)
               ON CONFLICT (id, user_id) DO UPDATE SET name = COALESCE(?, devices.name), last_seen_at = now(), revoked_at = NULL""",
        ).use { statement ->
            statement.setObject(1, deviceId); statement.setObject(2, userId); statement.setString(3, deviceName ?: "Android")
            statement.setString(4, deviceName); statement.executeUpdate()
        }
        val refresh = tokens.randomToken()
        connection.prepareStatement("INSERT INTO refresh_tokens(token_hash, user_id, device_id, expires_at) VALUES (?, ?, ?, ?)").use { statement ->
            statement.setString(1, tokens.sha256(refresh)); statement.setObject(2, userId); statement.setObject(3, deviceId)
            statement.setTimestamp(4, java.sql.Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS))); statement.executeUpdate()
        }
        val sessionGeneration = connection.prepareStatement("SELECT session_generation FROM users WHERE id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ApiException(401, "user_not_found", "账号不存在")
                result.getLong(1)
            }
        }
        val (access, expires) = tokens.accessToken(userId, deviceId, sessionGeneration)
        return AuthResponse(userId.toString(), access, refresh, expires.epochSecond)
    }

    private fun validateCredentials(username: String, password: String) {
        validateUsername(username)
        validatePassword(password)
    }

    private fun validateUsername(username: String) {
        if (!username.matches(Regex("[A-Za-z0-9_]{3,32}"))) throw ApiException(400, "invalid_username", "用户名需为 3–32 位字母、数字或下划线")
    }

    private fun validatePassword(password: String) {
        if (password.length < 10) throw ApiException(400, "weak_password", "密码至少需要 10 位")
    }
}

class FamilyService(
    private val database: Database,
    private val tokens: TokenService,
    private val eventHub: BookEventHub,
    private val json: Json,
) {
    fun createInvite(userId: UUID, request: CreateInviteRequest): InviteResponse = database.transaction { connection ->
        if (request.role == BookRole.OWNER) throw ApiException(400, "invalid_role", "邀请角色不能是所有者")
        val bookId = UUID.fromString(request.bookId)
        requireRole(connection, userId, bookId, BookRole.OWNER)
        val hours = request.expiresInHours.coerceIn(1, 168)
        val code = tokens.humanCode()
        val expires = Instant.now().plus(hours.toLong(), ChronoUnit.HOURS)
        connection.prepareStatement("INSERT INTO invites(code_hash, book_id, role, created_by, expires_at) VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, tokens.sha256(code)); statement.setObject(2, bookId); statement.setString(3, request.role.name)
            statement.setObject(4, userId); statement.setTimestamp(5, java.sql.Timestamp.from(expires)); statement.executeUpdate()
        }
        InviteResponse(code, bookId.toString(), request.role, expires.toEpochMilli())
    }

    fun acceptInvite(userId: UUID, request: AcceptInviteRequest): UUID = database.transaction { connection ->
        val invite = connection.prepareStatement(
            "SELECT book_id, role FROM invites WHERE code_hash = ? AND used_at IS NULL AND expires_at > now() FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tokens.sha256(request.code.uppercase().trim()))
            statement.executeQuery().use { if (it.next()) it.getObject("book_id", UUID::class.java) to BookRole.valueOf(it.getString("role")) else null }
        } ?: throw ApiException(404, "invalid_invite", "邀请码无效或已过期")
        connection.prepareStatement(
            """INSERT INTO memberships(book_id, user_id, role, removed_at) VALUES (?, ?, ?, NULL)
               ON CONFLICT (book_id, user_id) DO UPDATE SET role = EXCLUDED.role, removed_at = NULL""",
        ).use { statement -> statement.setObject(1, invite.first); statement.setObject(2, userId); statement.setString(3, invite.second.name); statement.executeUpdate() }
        connection.prepareStatement("UPDATE invites SET used_by = ?, used_at = now() WHERE code_hash = ?").use { statement ->
            statement.setObject(1, userId); statement.setString(2, tokens.sha256(request.code.uppercase().trim())); statement.executeUpdate()
        }
        invite.first
    }

    fun listMemberships(userId: UUID, bookId: UUID): List<MemberDto> = database.dataSource.connection.use { connection ->
        requireRole(connection, userId, bookId, BookRole.VIEWER)
        connection.prepareStatement(
            "SELECT u.id, u.username, m.role FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.book_id = ? AND m.removed_at IS NULL ORDER BY m.joined_at",
        ).use { statement ->
            statement.setObject(1, bookId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(MemberDto(result.getString("id"), result.getString("username"), BookRole.valueOf(result.getString("role")))) } }
        }
    }

    fun listMyMemberships(userId: UUID): List<MyMembershipDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT book_id, role FROM memberships WHERE user_id = ? AND removed_at IS NULL ORDER BY joined_at",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(MyMembershipDto(result.getObject("book_id").toString(), BookRole.valueOf(result.getString("role"))))
                    }
                }
            }
        }
    }

    fun listAuditEvents(userId: UUID, bookId: UUID, requestedLimit: Int): List<AuditEventDto> = database.dataSource.connection.use { connection ->
        requireRole(connection, userId, bookId, BookRole.VIEWER)
        connection.prepareStatement(
            """SELECT a.id, a.actor_id, u.username, a.entity_type, a.entity_id, a.action,
                      a.changed_fields::text, a.occurred_at
               FROM audit_events a JOIN users u ON u.id = a.actor_id
               WHERE a.book_id = ? ORDER BY a.occurred_at DESC LIMIT ?""",
        ).use { statement ->
            statement.setObject(1, bookId)
            statement.setInt(2, requestedLimit.coerceIn(1, 200))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        AuditEventDto(
                            id = "server-${result.getLong("id")}",
                            bookId = bookId.toString(),
                            actorId = result.getObject("actor_id").toString(),
                            actorName = result.getString("username"),
                            entityType = SyncEntityType.valueOf(result.getString("entity_type")),
                            entityId = result.getObject("entity_id").toString(),
                            action = result.getString("action"),
                            changedFields = json.decodeFromString(result.getString("changed_fields")),
                            occurredAtEpochMillis = result.getTimestamp("occurred_at").time,
                        ),
                    )
                }
            }
        }
    }

    fun updateMemberRole(ownerId: UUID, bookId: UUID, memberId: UUID, role: BookRole) {
        database.transaction { connection ->
            requireRole(connection, ownerId, bookId, BookRole.OWNER)
            if (role == BookRole.OWNER) throw ApiException(400, "invalid_role", "不能通过成员管理转移所有权")
            if (memberId == ownerId) throw ApiException(400, "owner_immutable", "所有者不能修改自己的角色")
            connection.prepareStatement("UPDATE memberships SET role = ? WHERE book_id = ? AND user_id = ? AND removed_at IS NULL").use { statement ->
                statement.setString(1, role.name); statement.setObject(2, bookId); statement.setObject(3, memberId)
                if (statement.executeUpdate() == 0) throw ApiException(404, "member_not_found", "成员不存在")
            }
            auditMembership(connection, bookId, ownerId, memberId, "ROLE_${role.name}")
        }
        eventHub.notifyUser(memberId, bookId)
    }

    fun removeMember(ownerId: UUID, bookId: UUID, memberId: UUID) {
        database.transaction { connection ->
            requireRole(connection, ownerId, bookId, BookRole.OWNER)
            if (memberId == ownerId) throw ApiException(400, "owner_immutable", "所有者不能移除自己")
            connection.prepareStatement("UPDATE memberships SET removed_at = now() WHERE book_id = ? AND user_id = ? AND removed_at IS NULL").use { statement ->
                statement.setObject(1, bookId); statement.setObject(2, memberId)
                if (statement.executeUpdate() == 0) throw ApiException(404, "member_not_found", "成员不存在")
            }
            auditMembership(connection, bookId, ownerId, memberId, "REMOVE")
        }
        eventHub.notifyUser(memberId, bookId)
    }

    private fun auditMembership(connection: Connection, bookId: UUID, actorId: UUID, memberId: UUID, action: String) {
        connection.prepareStatement("INSERT INTO audit_events(book_id, actor_id, entity_type, entity_id, action) VALUES (?, ?, 'MEMBERSHIP', ?, ?)").use { statement ->
            statement.setObject(1, bookId); statement.setObject(2, actorId); statement.setObject(3, memberId); statement.setString(4, action); statement.executeUpdate()
        }
    }

    companion object {
        fun requireRole(connection: Connection, userId: UUID, bookId: UUID, minimum: BookRole): BookRole {
            val role = connection.prepareStatement("SELECT role FROM memberships WHERE book_id = ? AND user_id = ? AND removed_at IS NULL").use { statement ->
                statement.setObject(1, bookId); statement.setObject(2, userId)
                statement.executeQuery().use { if (it.next()) BookRole.valueOf(it.getString(1)) else null }
            } ?: throw ApiException(403, "book_access_denied", "无权访问该账本")
            val rank = mapOf(BookRole.VIEWER to 0, BookRole.EDITOR to 1, BookRole.OWNER to 2)
            if (rank.getValue(role) < rank.getValue(minimum)) throw ApiException(403, "insufficient_role", "当前角色不能执行此操作")
            return role
        }
    }
}
