package com.billrecord.server

import com.billrecord.shared.AcceptInviteRequest
import com.billrecord.shared.ApiError
import com.billrecord.shared.BootstrapRequest
import com.billrecord.shared.CreateInviteRequest
import com.billrecord.shared.LoginRequest
import com.billrecord.shared.RefreshRequest
import com.billrecord.shared.RegisterRequest
import com.billrecord.shared.RecoverAccountRequest
import com.billrecord.shared.SyncRequest
import com.billrecord.shared.UpdateMemberRoleRequest
import com.billrecord.shared.InitAttachmentUploadRequest
import com.billrecord.shared.UpdateCredentialsRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { ledgerModule(config) }.start(wait = true)
}

fun Application.ledgerModule(config: AppConfig) {
    val serverLogger = org.slf4j.LoggerFactory.getLogger("BillRecordServer")
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val database = Database(config).also(Database::migrate)
    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) { database.close() }
    val tokenService = TokenService(config)
    val authService = AuthService(database, PasswordService(), tokenService)
    val eventHub = BookEventHub(database, json)
    val familyService = FamilyService(database, tokenService, eventHub, json)
    val syncService = SyncService(database, json, eventHub)
    val attachmentService = AttachmentService(database, config)

    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(json)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api/") || it.request.path() == "/health" }
    }
    install(CORS) {
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }
    install(StatusPages) {
        exception<ApiException> { call, error -> call.respond(HttpStatusCode.fromValue(error.status), ApiError(error.code, error.message)) }
        exception<IllegalArgumentException> { call, error -> call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", error.message ?: "请求参数错误")) }
        exception<Throwable> { call, error ->
            serverLogger.error("Unhandled request error", error)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "服务器处理失败"))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "bill-record"
            verifier(tokenService.verifier)
            validate { credential ->
                runCatching {
                    val userId = UUID.fromString(credential.payload.subject)
                    val deviceId = UUID.fromString(credential.payload.getClaim("deviceId").asString())
                    val sessionGeneration = credential.payload.getClaim("sessionGeneration").asLong() ?: return@runCatching null
                    if (authService.isDeviceActive(userId, deviceId, sessionGeneration)) JWTPrincipal(credential.payload) else null
                }.getOrNull()
            }
        }
    }

    routing {
        get("/health") {
            database.dataSource.connection.use { connection -> connection.prepareStatement("SELECT 1").use { it.execute() } }
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }
        route("/api/v1") {
            route("/auth") {
                post("/bootstrap") { call.respond(authService.bootstrap(call.receive<BootstrapRequest>())) }
                post("/login") { call.respond(authService.login(call.receive<LoginRequest>())) }
                post("/register") { call.respond(authService.register(call.receive<RegisterRequest>())) }
                post("/recover") { call.respond(authService.recover(call.receive<RecoverAccountRequest>())) }
                post("/refresh") { call.respond(authService.refresh(call.receive<RefreshRequest>())) }
            }
            authenticate("auth-jwt") {
                get("/me") {
                    call.respond(authService.profile(call.principal<JWTPrincipal>()!!.userId()))
                }
                post("/me/credentials") {
                    val principal = call.principal<JWTPrincipal>()!!
                    call.respond(authService.updateCredentials(principal.userId(), principal.deviceId(), call.receive<UpdateCredentialsRequest>()))
                }
                get("/devices") {
                    val principal = call.principal<JWTPrincipal>()!!
                    call.respond(authService.listDevices(principal.userId(), principal.deviceId()))
                }
                get("/memberships") {
                    call.respond(familyService.listMyMemberships(call.principal<JWTPrincipal>()!!.userId()))
                }
                delete("/devices/{id}") {
                    authService.revokeDevice(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"]))
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/invites") { call.respond(familyService.createInvite(call.principal<JWTPrincipal>()!!.userId(), call.receive<CreateInviteRequest>())) }
                post("/invites/accept") {
                    call.respond(mapOf("bookId" to familyService.acceptInvite(call.principal<JWTPrincipal>()!!.userId(), call.receive<AcceptInviteRequest>()).toString()))
                }
                get("/books/{id}/members") {
                    call.respond(familyService.listMemberships(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"])))
                }
                get("/books/{id}/audit-events") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    call.respond(familyService.listAuditEvents(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"]), limit))
                }
                post("/books/{id}/members/{userId}/role") {
                    familyService.updateMemberRole(
                        call.principal<JWTPrincipal>()!!.userId(),
                        UUID.fromString(call.parameters["id"]),
                        UUID.fromString(call.parameters["userId"]),
                        call.receive<UpdateMemberRoleRequest>().role,
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/books/{id}/members/{userId}") {
                    familyService.removeMember(
                        call.principal<JWTPrincipal>()!!.userId(),
                        UUID.fromString(call.parameters["id"]),
                        UUID.fromString(call.parameters["userId"]),
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/sync") {
                    val principal = call.principal<JWTPrincipal>()!!
                    call.respond(syncService.sync(principal.userId(), principal.deviceId(), call.receive<SyncRequest>()))
                }
                post("/attachments/uploads") {
                    call.respond(attachmentService.beginUpload(call.principal<JWTPrincipal>()!!.userId(), call.receive<InitAttachmentUploadRequest>()))
                }
                get("/attachments/uploads/{id}") {
                    call.respond(attachmentService.uploadStatus(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"])))
                }
                post("/attachments/uploads/{id}/chunks") {
                    val offset = call.request.headers["X-Upload-Offset"]?.toLongOrNull() ?: throw ApiException(400, "missing_offset", "缺少有效的 X-Upload-Offset")
                    call.respond(attachmentService.appendChunk(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"]), offset, call.receiveChannel().toInputStream()))
                }
                post("/attachments") {
                    val bookId = UUID.fromString(requireNotNull(call.request.headers["X-Book-Id"]))
                    val transactionId = UUID.fromString(requireNotNull(call.request.headers["X-Transaction-Id"]))
                    val name = call.request.headers["X-File-Name"] ?: "attachment"
                    val mime = call.request.contentType().toString()
                    val input = call.receiveChannel().toInputStream()
                    call.respond(attachmentService.upload(call.principal<JWTPrincipal>()!!.userId(), bookId, transactionId, name, mime, call.request.headers["X-SHA256"], input))
                }
                get("/attachments/{id}") {
                    val download = attachmentService.download(call.principal<JWTPrincipal>()!!.userId(), UUID.fromString(call.parameters["id"]))
                    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, download.displayName).toString())
                    call.response.header(HttpHeaders.ContentType, download.mimeType)
                    call.respondFile(download.file)
                }
                webSocket("/events") {
                    val userId = call.principal<JWTPrincipal>()!!.userId()
                    eventHub.attach(userId, this)
                    try { for (frame in incoming) if (frame is Frame.Text && frame.readText() == "ping") send(Frame.Text("pong")) }
                    finally { eventHub.detach(userId, this) }
                }
            }
        }
    }
}

private fun JWTPrincipal.userId() = UUID.fromString(payload.subject)
private fun JWTPrincipal.deviceId() = UUID.fromString(payload.getClaim("deviceId").asString())
