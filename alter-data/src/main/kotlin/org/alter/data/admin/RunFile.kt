package org.alter.data.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.alter.data.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * `data/run/server.json`: how local tools find and talk to the running server. Written at start, deleted on
 * shutdown. Holds the admin token, so it is created owner-readable only where the OS supports that.
 */
data class RunFile(val pid: Long, val adminPort: Int, val adminToken: String, val revision: Int, val startedAt: String) {
    fun write(path: Path) {
        AtomicFiles.writeText(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n")
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()

        fun read(path: Path): RunFile? = if (Files.exists(path)) runCatching { mapper.readValue<RunFile>(path.toFile()) }.getOrNull() else null

        /** 32 random bytes, URL-safe. */
        fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
