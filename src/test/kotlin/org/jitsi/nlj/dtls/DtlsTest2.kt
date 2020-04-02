/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.nlj.dtls

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.transform.node.PcapWriter
import org.jitsi.rtp.UnparsedPacket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.concurrent.thread

class DtlsTest2 : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    private val debugEnabled = true
    private val pcapEnabled = false
    private val logger = StdoutLogger(_level = Level.OFF)

    fun debug(s: String) {
        if (debugEnabled) {
            println(s)
        }
    }

    init {
        val dtlsServer = DtlsStack2(logger).apply { actAsServer() }
        val dtlsClient = DtlsStack2(logger).apply { actAsClient() }
        val pcapWriter = if (pcapEnabled) PcapWriter(logger, "/tmp/dtls-test.pcap") else null

        dtlsClient.remoteFingerprints = mapOf(
            dtlsServer.localFingerprintHashFunction to dtlsServer.localFingerprint)
        dtlsServer.remoteFingerprints = mapOf(
            dtlsClient.localFingerprintHashFunction to dtlsClient.localFingerprint)

        // The DTLS server's send is wired directly to the DTLS client's receive
        dtlsServer.sender = { buf, off, len ->
            pcapWriter?.processPacket(PacketInfo(UnparsedPacket(buf, off, len)))
            dtlsClient.processIncomingProtocolData(buf, off, len)
        }

        val serverReceivedData = CompletableFuture<String>()
        val serverToClientMessage = "Goodbye, world"
        dtlsServer.incomingDataHandler = object : DtlsStack2.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                val packetData = ByteBuffer.wrap(data, off, len)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Server received message: '$receivedStr'")
                serverReceivedData.complete(receivedStr)
                val serverToClientData = serverToClientMessage.toByteArray()

                dtlsServer.sendApplicationData(serverToClientData, 0, serverToClientData.size)
            }
        }

        // The DTLS client's send is wired directly to the DTLS server's receive
        dtlsClient.sender = { buf, off, len ->
            pcapWriter?.processPacket(PacketInfo(UnparsedPacket(buf, off, len)))
            dtlsServer.processIncomingProtocolData(buf, off, len)
        }

        val clientReceivedData = CompletableFuture<String>()
        dtlsClient.incomingDataHandler = object : DtlsStack2.IncomingDataHandler {
            override fun dataReceived(data: ByteArray, off: Int, len: Int) {
                val packetData = ByteBuffer.wrap(data, off, len)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Client received message: '$receivedStr'")
                clientReceivedData.complete(receivedStr)
            }
        }

        val serverThread = thread {
            debug("Server accepting")
            dtlsServer.start()
            debug("Server accepted connection")
        }

        Thread.sleep(3000)
        debug("Client connecting")
        dtlsClient.start()
        // Ensure the server has fully established things on its side as well before we send the
        // message by waiting for the server accept thread to finish
        serverThread.join()
        debug("Client connected, sending message")
        val clientToServerMessage = "Hello, world"
        val clientToServerData = clientToServerMessage.toByteArray()
        dtlsClient.sendApplicationData(clientToServerData, 0, clientToServerData.size)

        serverReceivedData.get(5, TimeUnit.SECONDS) shouldBe clientToServerMessage
        clientReceivedData.get(5, TimeUnit.SECONDS) shouldBe serverToClientMessage
    }
}
