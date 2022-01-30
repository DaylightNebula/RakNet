package raknet.handler

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import raknet.Magic
import raknet.Server
import raknet.connection.Connection
import raknet.enums.Flag
import raknet.packet.DataPacket
import raknet.packet.Datagram
import raknet.packet.PacketType
import raknet.packet.protocol.*
import java.net.InetSocketAddress
import kotlin.experimental.and

class IncomingDataHandler(private val server: Server): SimpleChannelInboundHandler<DatagramPacket>() {

    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        val sender = msg.sender()
        val buffer = msg.content()
        // Capture buffer data and transform it into a packet if possible
        // We could use a packet factory here, but I'm not sure if that's the best avenue to take at the moment
        val id = buffer.readUnsignedByte()
        val type: PacketType? = PacketType.find(id)
        if(!server.hasConnection(sender)) {
            if(type == null) return
            val response = handleUnconnected(ctx, type, buffer, sender)
            if (response == null) {
                server.log("Received unconnected packet of type $id from $sender", "WARN")
                return
            }
            ctx.sendPacket(sender, response)
        } else {
            val connection = server.getConnection(sender)!!
            if(!isDatagram(id)) {
                connection.log("Received non-datagram packet of type $id from $sender")
                return
            }
            val datagram = Datagram.from(id, buffer)
            for(frame in datagram.frames) {
                val frameType = PacketType.find(frame.body.readUnsignedByte())
                val packet: DataPacket? = when(frameType) {
                    PacketType.CONNECTED_PING -> ConnectedPing.from(frame.body)
                    PacketType.CONNECTION_REQUEST -> ConnectionRequest.from(frame.body)
                    PacketType.NEW_INCOMING_CONNECTION -> NewIncomingConnection.from(frame.body)
                    PacketType.DISCONNECTION_NOTIFICATION -> DisconnectionNotification()
                    else -> {
                        connection.log("Expression did not handle packet of type $frameType from $sender")
                        null
                    }
                }
                if(packet == null) {
                    connection.log("Received invalid packet of type %02x from $sender".format(frameType))
                    return
                }
                connection.handle(packet)
            }
        }
    }

    private fun isDatagram(id: Short): Boolean {
        return id and Flag.DATAGRAM.id() != 0.toShort()
    }

    private fun handleUnconnected(ctx: ChannelHandlerContext, type: PacketType, buffer: ByteBuf, sender: InetSocketAddress): DataPacket? {
        return when(type) {
            PacketType.UNCONNECTED_PING -> {
                val ping = UnconnectedPing.from(buffer)
                UnconnectedPong(
                    pingId = ping.time,
                    magic = Magic,
                    guid = server.guid.mostSignificantBits,
                    serverName = server.identifier.toString(),
                )
            }
            PacketType.OPEN_CONNECTION_REQUEST_1 -> {
                val request = OpenConnectionRequest1.from(buffer)
                OpenConnectionReply1(
                    magic = Magic,
                    serverGuid = server.guid.mostSignificantBits,
                    useSecurity = false,
                    mtuSize = (request.mtuSize + 28).toShort(),
                )
            }
            PacketType.OPEN_CONNECTION_REQUEST_2 -> {
                val request = OpenConnectionRequest2.from(buffer)
                server.addConnection(Connection(
                    context = ctx,
                    server = server,
                    address = sender,
                    mtuSize = request.mtuSize,
                    guid = request.clientGuid
                ))
                OpenConnectionReply2(
                    magic = Magic,
                    serverGuid = server.guid.mostSignificantBits,
                    mtuSize = request.mtuSize,
                    clientAddress = request.serverAddress,
                    encryptionEnabled = false,
                )
            }
            else -> null
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        cause?.printStackTrace()
    }

    private fun ChannelHandlerContext.sendPacket(address: InetSocketAddress, packet: DataPacket): ChannelFuture = writeAndFlush(DatagramPacket(packet.prepare(), address))
}