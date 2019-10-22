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

package org.jitsi.nlj.stats

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import io.kotlintest.IsolationMode
import io.kotlintest.matchers.doubles.plusOrMinus
import io.kotlintest.milliseconds
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.time.Duration
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket

class EndpointConnectionStatsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = spy()

    private var mostRecentPublishedRtt: Double = -1.0
    private var numRttUpdates: Int = 0
    private val stats = EndpointConnectionStats(StdoutLogger(), clock).also {
        it.addListener(object : EndpointConnectionStats.EndpointConnectionStatsListener {
            override fun onRttUpdate(newRtt: Double) {
                mostRecentPublishedRtt = newRtt
                numRttUpdates++
            }
        })
    }

    init {
        "after an SR is sent" {
            val mockSenderInfo = mock<RtcpSrPacket.SenderInfo> {
                on { compactedNtpTimestamp } doReturn 100
            }
            val srPacket = mock<RtcpSrPacket> {
                on { senderSsrc } doReturn 1234L
                on { senderInfo } doReturn mockSenderInfo
            }

            stats.rtcpPacketSent(srPacket)
            "and an RR is received which refers to it" {
                clock.elapse(60.milliseconds)

                val reportBlock = mock<RtcpReportBlock> {
                    on { ssrc } doReturn 1234L
                    on { lastSrTimestamp } doReturn 100
                    on { delaySinceLastSr } doReturn 50.milliseconds.toDelaySinceLastSrFraction()
                }

                val rtcpPacket = mock<RtcpRrPacket> {
                    on { reportBlocks } doReturn listOf(reportBlock)
                }

                stats.rtcpPacketReceived(rtcpPacket, clock.instant().toEpochMilli())
                "the rtt" {
                    should("be updated correctly") {
                        mostRecentPublishedRtt shouldBe(10.0.plusOrMinus(.1))
                    }
                }
            }
        }
        "when a report block is received for which we can't find an SR" {
            val reportBlock = mock<RtcpReportBlock> {
                on { ssrc } doReturn 1234L
                on { lastSrTimestamp } doReturn 100
                on { delaySinceLastSr } doReturn 50.milliseconds.toDelaySinceLastSrFraction()
            }

            val rtcpPacket = mock<RtcpRrPacket> {
                on { reportBlocks } doReturn listOf(reportBlock)
            }

            stats.rtcpPacketReceived(rtcpPacket, clock.instant().toEpochMilli())

            "the rtt" {
                should("not have been updated") {
                    numRttUpdates shouldBe 0
                }
            }
        }
        "when a report block is received with no lastSrTimestamp" {
            val reportBlock = mock<RtcpReportBlock> {
                on { ssrc } doReturn 1234L
                on { lastSrTimestamp } doReturn 0
                on { delaySinceLastSr } doReturn 50.milliseconds.toDelaySinceLastSrFraction()
            }

            val rtcpPacket = mock<RtcpRrPacket> {
                on { reportBlocks } doReturn listOf(reportBlock)
            }

            stats.rtcpPacketReceived(rtcpPacket, clock.instant().toEpochMilli())

            "the rtt" {
                should("not have been updated") {
                    numRttUpdates shouldBe 0
                }
            }
        }

        "when a report block is received with no delaySinceLastSr" {
            val reportBlock = mock<RtcpReportBlock> {
                on { ssrc } doReturn 1234L
                on { lastSrTimestamp } doReturn 100
                on { delaySinceLastSr } doReturn 0
            }

            val rtcpPacket = mock<RtcpRrPacket> {
                on { reportBlocks } doReturn listOf(reportBlock)
            }

            stats.rtcpPacketReceived(rtcpPacket, clock.instant().toEpochMilli())

            "the rtt" {
                should("not have been updated") {
                    numRttUpdates shouldBe 0
                }
            }
        }
    }

    // Takes a Duration and converts it to 1/65536ths of a second
    private fun Duration.toDelaySinceLastSrFraction(): Long = (toNanos() * .000065536).toLong()
}