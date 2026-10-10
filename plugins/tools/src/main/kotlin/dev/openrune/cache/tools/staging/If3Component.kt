package dev.openrune.cache.tools.staging

/**
 * The head of an if3 component definition (index 3, group = interface id, file = component id): what is needed to
 * tell which component a server binding means, namely type, parent, position, size, click mask, ops and text.
 * Decoding stops after the ops and the target verb; script listeners and the rest are not read.
 *
 * Layout as read off the revision 241 cache (OpenRS2 2735): a 0xFF marker, then type, content type, x, y, width,
 * height, the four size/position modes, parent, hidden flag, a type-specific block, a 24-bit click mask, the
 * component name, the op count and ops. Types this reader does not know leave [ops] and [clickMask] unset.
 */
data class If3Component(
    val interfaceId: Int,
    val componentId: Int,
    val type: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** The parent's component id within the same interface, or -1. */
    val parent: Int,
    val hidden: Boolean,
    val clickMask: Int?,
    val ops: List<String?>?,
    val text: String?,
    val graphic: Int?,
    val error: String? = null,
) {
    /** Op *n* (1-based) is transmitted to the server when bit *n* of the click mask is set. */
    fun transmitsOp(op: Int): Boolean = clickMask != null && (clickMask shr op) and 1 == 1

    val typeName: String get() = TYPE_NAMES[type] ?: "type$type"

    companion object {
        val TYPE_NAMES = mapOf(0 to "layer", 3 to "rect", 4 to "text", 5 to "graphic", 6 to "model", 9 to "line")

        fun decode(interfaceId: Int, componentId: Int, data: ByteArray): If3Component {
            val buf = Bytes(data)
            require(buf.u8() == 0xFF) { "$interfaceId:$componentId is not an if3 component" }
            val type = buf.u8()
            buf.u16() // content type
            val x = buf.s16()
            val y = buf.s16()
            val width = buf.u16()
            val height = if (type == 9) buf.s16() else buf.u16()
            val widthMode = buf.s8()
            val heightMode = buf.s8()
            buf.s8()
            buf.s8()
            val parent = buf.u16().let { if (it == 0xFFFF) -1 else it }
            val hidden = buf.u8() == 1
            var text: String? = null
            var graphic: Int? = null
            when (type) {
                0 -> { buf.u16(); buf.u16(); buf.u8() }
                3 -> { buf.s32(); buf.u8(); buf.u8() }
                4 -> { buf.u16(); text = buf.string(); buf.u8(); buf.u8(); buf.u8(); buf.u8(); buf.s32() }
                5 -> { graphic = buf.s32(); buf.u16(); buf.u8(); buf.u8(); buf.u8(); buf.s32(); buf.u8(); buf.u8() }
                6 -> {
                    buf.u16(); buf.s16(); buf.s16(); buf.u16(); buf.u16(); buf.u16(); buf.u16(); buf.u16(); buf.u8(); buf.u16()
                    if (widthMode != 0) buf.u16()
                    if (heightMode != 0) buf.u16()
                }
                9 -> { buf.u8(); buf.s32(); buf.u8() }
                else -> return If3Component(interfaceId, componentId, type, x, y, width, height, parent, hidden, null, null, null, null, "type $type not decoded")
            }
            return try {
                val clickMask = buf.u24()
                buf.string() // component name (opbase)
                val ops = List(buf.u8()) { buf.string().ifEmpty { null } }
                If3Component(interfaceId, componentId, type, x, y, width, height, parent, hidden, clickMask, ops, text, graphic)
            } catch (e: IndexOutOfBoundsException) {
                If3Component(interfaceId, componentId, type, x, y, width, height, parent, hidden, null, null, text, graphic, "truncated")
            }
        }
    }

    private class Bytes(private val data: ByteArray) {
        private var pos = 0
        fun u8(): Int = data[pos++].toInt() and 0xFF
        fun s8(): Int = data[pos++].toInt()
        fun u16(): Int = (u8() shl 8) or u8()
        fun s16(): Int = u16().toShort().toInt()
        fun u24(): Int = (u8() shl 16) or u16()
        fun s32(): Int = (u16() shl 16) or u16()
        fun string(): String {
            val start = pos
            while (data[pos].toInt() != 0) pos++
            return String(data, start, pos++ - start, Charsets.ISO_8859_1)
        }
    }
}
