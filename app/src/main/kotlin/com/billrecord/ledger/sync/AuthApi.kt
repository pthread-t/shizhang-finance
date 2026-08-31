package com.billrecord.ledger.sync

import android.os.Build
import com.billrecord.ledger.data.AppPreferences
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
import com.billrecord.shared.RefreshRequest
import com.billrecord.shared.RegisterRequest
import com.billrecord.shared.RecoverAccountRequest
import com.billrecord.shared.UpdateMemberRoleRequest
import com.billrecord.shared.AccountProfileDto
import com.billrecord.shared.UpdateCredentialsRequest
import com.billrecord.shared.CredentialsUpdateResponse
import dagger.hilt.android.scopes.ViewModelScoped
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthApi @Inject constructor(
    private val client: HttpClient,
    private val preferences: AppPreferences,
) {
    val signedIn: Boolean get() = preferences.accessToken() != null

    suspend fun login(username: String, password: String, bootstrap: Boolean, inviteCode: String? = null): AuthResponse {
        val request = LoginRequest(username, password, preferences.deviceId(), deviceName())
        val response = if (bootstrap) {
            val url = baseUrl() + "/api/v1/auth/bootstrap"
            client.post(url) { setBody(BootstrapRequest(request.username, request.password, request.deviceId, request.deviceName)) }.body<AuthResponse>()
        } else if (!inviteCode.isNullOrBlank()) {
            client.post(baseUrl() + "/api/v1/auth/register") { setBody(RegisterRequest(username, password, inviteCode, request.deviceId, request.deviceName)) }.body()
        } else client.post(baseUrl() + "/api/v1/auth/login") { setBody(request) }.body()
        preferences.saveSession(response.userId, response.accessToken, response.refreshToken)
        preferences.saveUsername(username.trim().lowercase())
        return response
    }

    suspend fun recover(username: String, recoveryCode: String, newPassword: String): AuthResponse {
        val response: AuthResponse = client.post(baseUrl() + "/api/v1/auth/recover") {
            setBody(RecoverAccountRequest(username, recoveryCode, newPassword, preferences.deviceId(), deviceName()))
        }.body()
        preferences.saveSession(response.userId, response.accessToken, response.refreshToken)
        preferences.saveUsername(username.trim().lowercase())
        return response
    }

    suspend fun createInvite(bookId: String, role: BookRole): InviteResponse = authorizedPost("/api/v1/invites", CreateInviteRequest(bookId, role))
    suspend fun acceptInvite(code: String): Map<String, String> = authorizedPost("/api/v1/invites/accept", AcceptInviteRequest(code))
    suspend fun devices(): List<DeviceDto> = authorized { token -> client.get(baseUrl() + "/api/v1/devices") { bearerAuth(token) }.body() }
    suspend fun profile(): AccountProfileDto = authorized { token -> client.get(baseUrl() + "/api/v1/me") { bearerAuth(token) }.body() }
    suspend fun updateCredentials(currentPassword: String, newUsername: String?, newPassword: String?): CredentialsUpdateResponse {
        val response: CredentialsUpdateResponse = authorized { token ->
            client.post(baseUrl() + "/api/v1/me/credentials") {
                bearerAuth(token)
                setBody(UpdateCredentialsRequest(currentPassword, newUsername, newPassword))
            }.body()
        }
        preferences.saveSession(response.session.userId, response.session.accessToken, response.session.refreshToken)
        preferences.saveUsername(response.profile.username)
        return response
    }
    suspend fun revokeDevice(id: String) { authorized { token -> client.delete(baseUrl() + "/api/v1/devices/$id") { bearerAuth(token) } } }
    suspend fun members(bookId: String): List<MemberDto> = authorized { token -> client.get(baseUrl() + "/api/v1/books/$bookId/members") { bearerAuth(token) }.body() }
    suspend fun auditEvents(bookId: String): List<AuditEventDto> = authorized { token -> client.get(baseUrl() + "/api/v1/books/$bookId/audit-events?limit=100") { bearerAuth(token) }.body() }
    suspend fun updateMemberRole(bookId: String, userId: String, role: BookRole) {
        authorized { token -> client.post(baseUrl() + "/api/v1/books/$bookId/members/$userId/role") { bearerAuth(token); setBody(UpdateMemberRoleRequest(role)) } }
    }
    suspend fun removeMember(bookId: String, userId: String) {
        authorized { token -> client.delete(baseUrl() + "/api/v1/books/$bookId/members/$userId") { bearerAuth(token) } }
    }
    suspend fun setServerUrl(value: String) = preferences.setServerUrl(value)
    fun signOut() = preferences.clearSession()

    private suspend inline fun <reified Request : Any, reified Response : Any> authorizedPost(path: String, body: Request): Response =
        authorized { token -> client.post(baseUrl() + path) { bearerAuth(token); setBody(body) }.body() }

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        val token = requireToken()
        return try { block(token) } catch (error: ClientRequestException) {
            if (error.response.status.value != 401) throw error
            val refreshed = try {
                refreshSession()
            } catch (refreshError: ClientRequestException) {
                if (refreshError.response.status.value == 401) preferences.clearSession()
                throw refreshError
            }
            block(refreshed)
        }
    }

    private suspend fun refreshSession(): String {
        val response: AuthResponse = client.post(baseUrl() + "/api/v1/auth/refresh") {
            setBody(RefreshRequest(requireNotNull(preferences.refreshToken()), preferences.deviceId()))
        }.body()
        preferences.saveSession(response.userId, response.accessToken, response.refreshToken)
        return response.accessToken
    }

    private suspend fun baseUrl() = preferences.serverUrl.first().trimEnd('/')
    private fun requireToken() = requireNotNull(preferences.accessToken()) { "请先登录云同步" }
    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
