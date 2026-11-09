package com.hiresplayer.format

/**
 * Random-access view of a media file. Implementations may be a byte array (tests)
 * or a content URI channel (the app). Probes only read headers.
 */
interface BinarySource {
    val length: Long

    /** Reads up to [size] bytes at [offset]. Returns a shorter array at EOF. */
    fun readAt(offset: Long, size: Int): ByteArray
}

class ByteArraySource(
    private val data: ByteArray,
    override val length: Long = data.size.toLong(),
) : BinarySource {
    override fun readAt(offset: Long, size: Int): ByteArray {
        if (size <= 0 || offset >= data.size || offset < 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(data.size, start + size)
        return data.copyOfRange(start, end)
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or
        (u8(i + 1).toLong() shl 16) or
        (u8(i + 2).toLong() shl 8) or
        u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or
        (u8(i + 1).toLong() shl 8) or
        (u8(i + 2).toLong() shl 16) or
        (u8(i + 3).toLong() shl 24)

internal fun ByteArray.u64be(i: Int): Long {
    var value = 0L
    for (shift in 0 until 8) {
        value = (value shl 8) or u8(i + shift).toLong()
    }
    return value
}

internal fun ByteArray.ascii(i: Int, n: Int): String {
    if (i < 0 || n < 0 || i + n > size) return ""
    return String(this, i, n, Charsets.US_ASCII)
}

internal fun ByteArray.indexOfAscii(needle: String, start: Int = 0): Int {
    if (needle.isEmpty() || start >= size) return -1
    val raw = needle.toByteArray(Charsets.US_ASCII)
    var i = start
    while (i + raw.size <= size) {
        var ok = true
        for (j in raw.indices) {
            if (this[i + j] != raw[j]) {
                ok = false
                break
            }
        }
        if (ok) return i
        i++
    }
    return -1
}
