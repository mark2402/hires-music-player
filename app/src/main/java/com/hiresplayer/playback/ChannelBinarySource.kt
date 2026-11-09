package com.hiresplayer.playback

import com.hiresplayer.format.BinarySource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ChannelBinarySource(private val channel: FileChannel) : BinarySource {
    override val length: Long = channel.size()

    override fun readAt(offset: Long, size: Int): ByteArray {
        if (size <= 0 || offset < 0 || offset >= length) return ByteArray(0)
        val count = minOf(size.toLong(), length - offset).toInt()
        val buffer = ByteBuffer.allocate(count)
        var read = 0
        while (read < count) {
            val n = channel.read(buffer, offset + read)
            if (n <= 0) break
            read += n
        }
        return if (read == count) buffer.array() else buffer.array().copyOf(read)
    }
}
