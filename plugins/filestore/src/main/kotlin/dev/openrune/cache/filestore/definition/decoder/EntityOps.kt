package dev.openrune.cache.filestore.definition.decoder

import dev.openrune.cache.filestore.buffer.Reader
import dev.openrune.cache.filestore.definition.data.ConditionalOp

/** Sub-options and conditional options: config opcodes added between revisions 229 and 241. */
internal object EntityOps {
    /** Opcode 43/100/200: one option index, then `(subId + 1, text)` pairs until a 0 byte. */
    fun readSubOps(buffer: Reader, into: MutableMap<Int, MutableMap<Int, String>>) {
        val index = buffer.readUnsignedByte()
        val subs = into.getOrPut(index) { mutableMapOf() }
        while (true) {
            val subId = buffer.readUnsignedByte() - 1
            if (subId == -1) break
            subs[subId] = buffer.readString()
        }
    }

    /** Opcode 101/201/252. */
    fun readConditionalOp(buffer: Reader): ConditionalOp {
        val index = buffer.readUnsignedByte()
        val varp = buffer.readUnsignedShort()
        val varbit = buffer.readUnsignedShort()
        val min = buffer.readInt()
        val max = buffer.readInt()
        return ConditionalOp(index, -1, varp, varbit, min, max, buffer.readString())
    }

    /** Opcode 102/202. */
    fun readConditionalSubOp(buffer: Reader): ConditionalOp {
        val index = buffer.readUnsignedByte()
        val subId = buffer.readUnsignedShort()
        val varp = buffer.readUnsignedShort()
        val varbit = buffer.readUnsignedShort()
        val min = buffer.readInt()
        val max = buffer.readInt()
        return ConditionalOp(index, subId, varp, varbit, min, max, buffer.readString())
    }
}
