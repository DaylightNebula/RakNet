package org.golem.raknet

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.ResourceLeakDetector
import org.golem.events.EventBus
import org.golem.raknet.connection.Connection
import org.golem.raknet.connection.DisconnectionReason
import org.golem.raknet.handler.MessageEncoder
import org.golem.raknet.handler.connected.ConnectedMessageDecoder
import org.golem.raknet.handler.connected.ConnectedMessageHandler
import org.golem.raknet.handler.unconnected.UnconnectedMessageDecoder
import org.golem.raknet.handler.unconnected.UnconnectedMessageHandler
import org.golem.raknet.message.OnlineMessage
import org.golem.raknet.message.OfflineMessage
import java.net.InetSocketAddress
import java.util.*
import kotlin.collections.HashMap

const val CURRENT_PROTOCOL_VERSION: Byte = 11

class Server(
    val port: Int = 19132,
    val guid: UUID = UUID.randomUUID(),
    var name: String = ""
) {
    init { ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.SIMPLE) }

    private val group = NioEventLoopGroup()
    private val startTime: Long = System.currentTimeMillis()
    val uptime: Long
        get() = System.currentTimeMillis() - startTime
    private val connections: HashMap<InetSocketAddress, Connection> = HashMap()

    private lateinit var future: ChannelFuture

    private val eventBus = EventBus<ServerEvent>()


    fun start(): ChannelFuture {
        val bootstrap = Bootstrap()
            .channel(NioDatagramChannel::class.java)
            .group(group)
            .option(ChannelOption.SO_REUSEADDR, true)
            .handler(object: ChannelInitializer<NioDatagramChannel>() {
                override fun initChannel(channel: NioDatagramChannel) {
                    channel.pipeline().addLast(
                        // Decoders
                        UnconnectedMessageDecoder(),
                        ConnectedMessageDecoder(this@Server),
                        // Encoders
                        MessageEncoder<OfflineMessage>(),
                        MessageEncoder<OnlineMessage>(),
                        // Handlers
                        UnconnectedMessageHandler(this@Server),
                        ConnectedMessageHandler(this@Server)
                    )
                }
            })
        // Bind the server to the port.
        val future = bootstrap.bind(port).sync()
        // Keep the server alive until the socket is closed.
        this.future = future.channel().closeFuture()
        eventBus.dispatch(ServerEvent.Start)
        return this.future
    }

    fun addConnection(connection: Connection) {
        eventBus.dispatch(ServerEvent.NewConnection(connection))
        connections[connection.address] = connection
    }

    fun getConnection(address: InetSocketAddress): Connection? = connections[address]

    fun hasConnection(address: InetSocketAddress): Boolean = connections.containsKey(address)

    fun getConnections(): List<Connection> = connections.values.toList()

    fun removeConnection(address: InetSocketAddress) {
        connections.remove(address)
    }

    fun removeConnection(connection: Connection) {
        connections.remove(connection.address)
    }

    fun getEventBus(): EventBus<ServerEvent> = eventBus

    fun isAlive(): Boolean = future.channel().isOpen

    fun shutdown() {
        eventBus.dispatch(ServerEvent.Shutdown)
        getConnections().forEach { it.close(DisconnectionReason.ServerClosed) }
        group.shutdownGracefully()
    }
}