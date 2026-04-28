package io.github.msksgm

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.freemarker.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receive
import io.ktor.server.sessions.*
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rubyeye.xmemcached.MemcachedClient
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.File
import java.time.OffsetDateTime
import kotlin.random.Random

data class User(
    val id: Int,
    val accountName: String,
    val passhash: String,
    val authority: Int,
    val delFlg: Int,
    val createdAt: OffsetDateTime
)

class MemcachedSessionStorage(
    private val client: MemcachedClient,
    private val keyPrefix: String = "isuconp-kotlin.session:",
    private val expirationSeconds: Int = 3600,
) : SessionStorage {
    override suspend fun write(id: String, value: String) {
        withContext(Dispatchers.IO) {
            client.set("$keyPrefix$id", expirationSeconds, value)
        }
    }

    override suspend fun read(id: String): String {
        return withContext(Dispatchers.IO) {
            client.get<String>("$keyPrefix$id")
                ?: throw NoSuchElementException("Session $id not found")
        }
    }

    override suspend fun invalidate(id: String) {
        withContext(Dispatchers.IO) {
            client.delete("$keyPrefix$id")
        }
    }
}

private val dataSource: HikariDataSource by lazy {
    HikariDataSource(HikariConfig().apply {
        val host = System.getenv("ISUCONP_DB_HOST") ?: "localhost"
        val port = System.getenv("ISUCONP_DB_PORT") ?: "3306"
        val name = System.getenv("ISUCONP_DB_NAME") ?: "isuconp"
        jdbcUrl = "jdbc:mysql://$host:$port/$name?useSSL=false&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&allowPublicKeyRetrieval=true"
        username = System.getenv("ISUCONP_DB_USER") ?: "root"
        password = System.getenv("ISUCONP_DB_PASSWORD") ?: "root"
        maximumPoolSize = 10
    })
}

private val jdbi: Jdbi by lazy {
    Jdbi.create(dataSource).installPlugin(KotlinPlugin())
}

private fun dbInitialize() {
    val sqls = listOf(
        "DELETE FROM users WHERE id > 1000",
        "DELETE FROM posts WHERE id > 10000",
        "DELETE FROM comments WHERE id > 100000",
        "UPDATE users SET del_flg = 0",
        "UPDATE users SET del_flg = 1 WHERE id % 50 = 0",
    )
    jdbi.useHandle<Exception> { h ->
        sqls.forEach { h.execute(it) }
    }
}

private fun digest(src: String): String {
    val cmd = """printf "%s" ${escapeshellarg(src)} | openssl dgst -sha512 | sed 's/^.*= //'"""
    val process = ProcessBuilder("/bin/bash", "-c", cmd)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return output.trim()
}

// goの実装を参考にしたので、自前でエスケープ関数を作成
private fun escapeshellarg(s: String): String {
    return "'" + s.replace("'", "'\\''") + "'"
}

private fun calculateSalt(accountName: String): String = digest(accountName)

private fun calculatePasshash(accountName: String, password: String): String {
    return digest("$password:${calculateSalt(accountName)}")
}

private fun secureRandomStr(length: Int): String {
    return Random.nextBytes(length).joinToString("") { "%02x".format(it) }
}

private fun tryLogin(accountName: String?, password: String?): User? {
    if (accountName == null || password == null) {
        return null
    }
    val user = jdbi.withHandle<User?, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE account_name = :name AND del_flg = 0")
            .bind("name", accountName)
            .mapTo<User>()
            .findOne()
            .orElse(null)
    }

    return if (calculatePasshash(accountName, password) == user?.passhash) {
        user
    } else {
        null
    }
}

private fun ApplicationCall.getSessionUser(): User? {
    val session = sessions.get<UserSession>() ?: return null
    val userId = session.userId

    val user = jdbi.withHandle<User?, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE id = :user_id")
            .bind("user_id", userId)
            .mapTo<User>()
            .findOne()
            .orElse(null)
    }

    return user
}

private fun ApplicationCall.getFlash(): String {
    val session = sessions.get<UserSession>() ?: return ""
    val notice = session.notice
    sessions.set(session.copy(notice = ""))
    return notice
}

private suspend fun RoutingContext.getInitialize() {
    dbInitialize()
    call.respond(HttpStatusCode.OK)
}

private suspend fun RoutingContext.getLogin() {
    val me = call.getSessionUser()

    if (me != null) {
        call.respondRedirect("/")
        return
    }

    val flash = call.getFlash()

    call.respond(
        FreeMarkerContent(
            "login.ftl",
            mapOf("flash" to flash),
            ""
        )
    )
}

private suspend fun RoutingContext.postLogin() {
    if (call.getSessionUser() != null) {
        call.respondRedirect("/")
        return
    }

    val params = call.receive<Parameters>()
    val accountName = params["account_name"]
    val password = params["password"]

    val user = tryLogin(accountName, password)

    if (user != null) {
        call.sessions.set(UserSession(userId = user.id, csrfToken = secureRandomStr(16)))
        call.respondRedirect("/")
    } else {
        call.sessions.set(UserSession(notice = "アカウント名かパスワードが間違っています"))
        call.respondRedirect("/login")
    }
}

private suspend fun RoutingContext.getRegister() {
    if (call.getSessionUser() != null) {
        call.respondRedirect("/")
        return
    }

    call.respond(
        FreeMarkerContent(
            "register.ftl",
            mapOf("flash" to call.getFlash()),
        )
    )
}

private suspend fun RoutingContext.getLogout() {
    call.sessions.clear<UserSession>()
    call.respondRedirect("/")
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/initialize") { getInitialize() }
        get("/login") { getLogin() }
        post("/login") { postLogin() }
        get("/register") { getRegister() }
        get("/logout") { getLogout() }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }

        staticFiles("/", File("/home/public"))
    }
}