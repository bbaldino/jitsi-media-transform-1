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

package org.jitsi.nlj.transform.node.incoming

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.resources.srtp_samples.SrtpSample
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.rtp.rtcp.RtcpPacket

internal class SrtcpTransformerDecryptNodeTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val srtcpTransformer = SrtpUtil.initializeTransformer(
            SrtpSample.srtpProfileInformation,
            SrtpSample.keyingMaterial.array(),
            SrtpSample.tlsRole,
            true
    )

    init {
        "decrypting a packet" {
            val decryptedPacket = srtcpTransformer.reverseTransform(SrtpSample.incomingEncryptedRtcpPacket)

            should("decrypt the data correctly") {
                //NOTE(brian): the incoming encrypted rtcp data represents a compound RTCP packet, so
                // SrtpSample.incomingEncryptedRtcpPacket parses only the first RTCP packet in the compound
                // data.  SrtpSample.expectedDecryptedRtcpData represents the entire decrypted buffer, but
                // we'll only decrypt the first packet (since that's all that is parsed in
                // SrtpSample.incomingEncryptedRtcpPacket), so we need to also parse the first RTCP packet
                // from the decrypted data here before we verify the decryption worked correctly
                RtcpPacket.fromBuffer(SrtpSample.expectedDecryptedRtcpData).getBuffer().compareTo(decryptedPacket.getBuffer()) shouldBe 0
            }
        }
    }
}