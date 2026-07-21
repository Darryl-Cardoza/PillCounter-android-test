package org.rite.hl7.encoding

/**
 * MLLP (Minimal Lower Layer Protocol) framing used to transport HL7 over TCP.
 * Each frame is: <VT 0x0B> message <FS 0x1C><CR 0x0D>.
 */
object Mllp {
    private const val SB: Byte = 0x0B  // start block (vertical tab)
    private const val EB: Byte = 0x1C  // end block (file separator)
    private const val CR: Byte = 0x0D  // carriage return

    /** Wraps a raw HL7 string in MLLP framing bytes. */
    fun wrap(hl7: String): ByteArray {
        val body = hl7.encodeToByteArray()
        val out = ByteArray(body.size + 3)
        out[0] = SB
        body.copyInto(out, destinationOffset = 1)
        out[body.size + 1] = EB
        out[body.size + 2] = CR
        return out
    }

    /** Strips MLLP framing bytes and returns the raw HL7 message text. */
    fun strip(bytes: ByteArray): String {
        var start = 0
        var end = bytes.size
        if (bytes.isNotEmpty() && bytes[0] == SB) start = 1
        var i = start
        while (i < bytes.size - 1) {
            if (bytes[i] == EB && bytes[i + 1] == CR) {
                end = i
                break
            }
            i++
        }
        return bytes.copyOfRange(start, end).decodeToString()
    }
}
