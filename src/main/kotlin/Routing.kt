package io.github.msksgm

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.freemarker.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.sessions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rubyeye.xmemcached.MemcachedClient
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.File

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

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/initialize") {
            dbInitialize()
            call.respond(HttpStatusCode.OK)
        }
        get("/login") {
            call.respond(
                FreeMarkerContent(
                    "login.ftl",
                    mapOf("flash" to ""),
                    ""
                )
            )
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
        get("/session/increment") {
            val session = call.sessions.get<MySession>() ?: MySession()
            call.sessions.set(session.copy(count = session.count + 1))
            call.respondText("Counter is ${session.count}. Refresh to increment.")
        }

        staticFiles("/", File("/home/public"))
    }
}