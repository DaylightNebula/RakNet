package raknet.message

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import raknet.encode

abstract class DataMessage(open val id: Int) : Message {

    override fun encode(): ByteBuf {
        val buffer = ByteBufAllocator.DEFAULT.ioBuffer()
        encodeOrder().forEach { it.encode(buffer) }
        return buffer
    }

    /**
     * We don't use decode at the moment as we use XPacket.from() as a way to decode the packet
     * It may be worth a look at using decode in the future
     */
    override fun decode(buffer: ByteBuf) = Unit

    abstract fun encodeOrder(): Array<Any>

    open fun encodeHeader(buffer: ByteBuf): ByteBuf = buffer.writeByte(id)

    fun prepare(): ByteBuf {
        val encoded = encode()
        try {
            val buffer = ByteBufAllocator.DEFAULT.ioBuffer()
            encodeHeader(buffer)
            buffer.writeBytes(encoded)
            return buffer
        } finally {
            encoded.release()
        }
    }

    override fun toString(): String = "DataPacket(id=$id)"

}

abstract class OnlineMessage(override val id: Int) : DataMessage(id)
abstract class OfflineMessage(override val id: Int): DataMessage(id)