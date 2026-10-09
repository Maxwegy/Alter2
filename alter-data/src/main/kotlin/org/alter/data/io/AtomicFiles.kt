package org.alter.data.io

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes that never leave a half-written file behind: write a sibling temp file, then atomically move it. */
object AtomicFiles {
    fun write(target: Path, bytes: ByteArray) {
        target.toAbsolutePath().parent?.let(Files::createDirectories)
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun writeText(target: Path, text: String) = write(target, text.toByteArray(Charsets.UTF_8))
}
