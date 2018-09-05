/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.service.neomedia.RawPacket
import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import java.nio.ByteBuffer

class SrtpTransformerDecryptNode : AbstractSrtpTransformerNode("SRTP decrypt wrapper") {
    override fun doTransform(pkts: List<Packet>, transformer: SinglePacketTransformer): List<Packet> {
        val decryptedPackets = mutableListOf<RtpPacket>()
        pkts.forEach {
            val packetBuf = it.getBuffer()
            val rp = RawPacket(packetBuf.array(), packetBuf.arrayOffset(), packetBuf.limit())
            transformer.reverseTransform(rp)?.let { decryptedRawPacket ->
                val rtpPacket = RtpPacket(
                    ByteBuffer.wrap(
                    decryptedRawPacket.buffer,
                    decryptedRawPacket.offset,
                    decryptedRawPacket.length))
                decryptedPackets.add(rtpPacket)
            }
        }
        return decryptedPackets
    }
}