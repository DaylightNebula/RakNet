package org.golem.raknet
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.nio.NioEventLoopGroup
import org.golem.raknet.connection.Connection
import org.golem.raknet.connection.ConnectionEvent
import org.golem.raknet.message.OnlineMessage
import org.golem.raknet.message.UserMessage
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val server = PersonalServer (
        port = 19132,
        name = "Test Server"
    )
    server.start()
}

const val TICK_RATE = 20

class PersonalConnection(
    private val connection: Connection
) {

    init {
        connection.getEventBus().listen(this) {
            when(it) {
                is ConnectionEvent.Connected -> this.handleConnect()
                is ConnectionEvent.Disconnected -> this.handleDisconnect()
                is ConnectionEvent.ReceivedMessage -> this.handleMessage(it.message)
                is ConnectionEvent.LatencyUpdated -> {
                    log("Latency updated to ${it.latency}")
                }
            }
        }
    }

    private fun handleConnect() {
        log("Client connected")
    }

    private fun handleMessage(message: OnlineMessage) {
        log("Received message: $message")
        if(message.id != 0xFE) {
            println("Incoming message was not 0xFE")
            return
        }

        // unpack message for debugging purposes
        when(message) {
            is UserMessage -> {
                handleUserMessage(message)
            }
            else -> println("Unknown input message in test")
        }

        // send back random packet (I think)
        val bytes = arrayOf<Byte>(0x63, 0x65, 0x62, 0x60, 0x60, 0x60, 0x04, 0x00).toByteArray()
        val buffer = ByteBufAllocator.DEFAULT.ioBuffer()
        buffer.writeBytes(bytes)
        val status = UserMessage(0xFE, buffer)
        connection.send(status, true)
    }

    private fun handleUserMessage(message: UserMessage) {
        val buffer = message.buffer
        println("User message bytes: ${buffer.readableBytes()}")
        (0 until buffer.readableBytes()).forEach {
            val byte = buffer.getByte(it)
            print(byte.toUInt().toString(16).padStart(2, '0'))
            print(" ")
        }
        // TODO uncompress with zlib before print

//        buffer.forEach {
//            print(it.toUInt().toString(16).padStart(2, '0'))
//            print(" ")
//        }
        println()
    }

    private fun handleDisconnect() {
        log("Client disconnected")
        connection.getEventBus().remove(this)
    }

    fun log(message: String, level: String = "INFO") {
        println("[%s] [%s] [%s] %s".format(
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS").format(Date()),
            "Connection: ${connection.address}",
            level,
            message
        ))
    }
}

class PersonalServer(
    port: Int,
    private val name: String,
    threadCount: Int = 4
) {
    val connections = mutableListOf<PersonalConnection>()
    val workerGroup = NioEventLoopGroup(threadCount)
    val guid = UUID.randomUUID()
    val raknet = Server(
        port = port,
        guid = guid,
        name = "MCPE;Golem Server;475;1.18.0;0;100;${guid.mostSignificantBits};Golem;Creative;1;19132;19132"
    )

    val running = AtomicBoolean(true)

    fun start() {
        log("Starting server...")

        log("Scheduling worker group...")
        workerGroup.scheduleAtFixedRate(this::tick, 0, 1000L / TICK_RATE, TimeUnit.MILLISECONDS)
        log("Starting RakNet...")
        raknet.getEventBus().listen { event ->
            when(event) {
                is ServerEvent.Start -> {
                    log("Server started successfully!")
                }
                is ServerEvent.NewConnection -> {
                    val connection = event.connection
                    log("New connection from ${connection.address}")
                    connections.add(PersonalConnection(connection))
                }
                is ServerEvent.Shutdown -> {
                    log("Server shutting down...")
                }
            }
        }
        raknet.start()
        while(running.get()) {
            if(!raknet.isAlive()) {
                log("RakNet died! Stopping server...")
                running.set(false)
                break
            }
            tick()
            // Sleep for a millisecond
            Thread.sleep(1)
        }
        raknet.shutdown()
        workerGroup.shutdownGracefully()
    }

    private fun tick() {

    }

    fun log(message: String, level: String = "INFO") {
        println("[%s] [%s] [%s] %s".format(
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS").format(Date()),
            "Server",
            level,
            message
        ))
    }

    fun shutdown() {
        running.set(false)
    }

}