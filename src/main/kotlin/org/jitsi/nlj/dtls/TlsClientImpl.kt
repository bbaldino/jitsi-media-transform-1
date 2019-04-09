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
package org.jitsi.nlj.dtls

import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.ExtensionType
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SRTPProtectionProfile
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.TlsSession
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer
import java.util.Hashtable

/**
 * Implementation of [DefaultTlsClient].
 */
class TlsClientImpl(
    /**
     * The function to call when the server certificateInfo is available.
     */
    private val notifyServerCertificate: (Certificate?) -> Unit
) : DefaultTlsClient(BC_TLS_CRYPTO) {

    private val logger = getLogger(this.javaClass)

    private val certificateInfo = DtlsStack.getCertificateInfo()

    private var session: TlsSession? = null

    private var clientCredentials: TlsCredentials? = null

    /**
     * Only set after a handshake has completed
     */
    lateinit var srtpKeyingMaterial: ByteArray

    private val srtpProtectionProfiles = intArrayOf(
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
    )

    var chosenSrtpProtectionProfile: Int = 0

    override fun getSessionToResume(): TlsSession? = session

    override fun getAuthentication(): TlsAuthentication {
        return object : TlsAuthentication {
            override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials {
                // NOTE: can't set clientCredentials when it is declared because 'context' won't be set yet
                if (clientCredentials == null) {
                    val crypto = context.crypto
                    when (crypto) {
                        is BcTlsCrypto -> {
                            clientCredentials = BcDefaultTlsCredentialedSigner(
                                TlsCryptoParameters(context),
                                crypto,
                                PrivateKeyFactory.createKey(certificateInfo.keyPair.private.encoded),
                                certificateInfo.certificate,
                                SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
                            )
                        }
                        else -> {
                            throw DtlsUtils.DtlsException("Unsupported crypto type: ${crypto.javaClass}")
                        }
                    }
                }
                return clientCredentials!!
            }

            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                this@TlsClientImpl.notifyServerCertificate(serverCertificate.certificate)
            }
        }
    }

    override fun getClientExtensions(): Hashtable<*, *> {
        var clientExtensions = super.getClientExtensions()
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null) {
            if (clientExtensions == null) {
                clientExtensions = Hashtable<Int, ByteArray>()
            }

            TlsSRTPUtils.addUseSRTPExtension(
                clientExtensions,
                UseSRTPData(srtpProtectionProfiles, TlsUtils.EMPTY_BYTES)
            )
        }
        clientExtensions.put(ExtensionType.renegotiation_info, byteArrayOf(0))


        return clientExtensions
    }

    override fun processServerExtensions(serverExtensions: Hashtable<*, *>?) {
        //TODO: a few cases we should be throwing alerts for in here.  see old TlsClientImpl
        val useSRTPData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions)
        val protectionProfiles = useSRTPData.protectionProfiles
        chosenSrtpProtectionProfile = DtlsUtils.chooseSrtpProtectionProfile(srtpProtectionProfiles, protectionProfiles)
    }

    override fun getCipherSuites(): IntArray {
        return intArrayOf(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
        )
    }

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        context.resumableSession?.let { newSession ->
            val newSessionIdHex = ByteBuffer.wrap(newSession.sessionID).toHex()

            session?.let { existingSession ->
                if (existingSession.sessionID?.contentEquals(newSession.sessionID) == true) {
                    logger.cinfo { "Resumed DTLS session $newSessionIdHex" }
                }
            } ?: run {
                logger.cinfo { "Established DTLS session $newSessionIdHex" }
                this.session = newSession
            }
        }
        val srtpProfileInformation =
                SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        srtpKeyingMaterial = context.exportKeyingMaterial(
                ExporterLabel.dtls_srtp,
                null,
                2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength)
        )
    }

    override fun notifyServerVersion(serverVersion: ProtocolVersion?) {
        super.notifyServerVersion(serverVersion)

        logger.cinfo { "Negotiated DTLS version $serverVersion" }
    }

    override fun getSupportedVersions(): Array<ProtocolVersion> =
            ProtocolVersion.DTLSv12.downTo(ProtocolVersion.DTLSv12)

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
        val stack = with(StringBuffer()) {
            val e = Exception()
            for (el in e.stackTrace) {
                appendln(el.toString())
            }
            toString()
        }
        logger.info(stack)
    }

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
        logger.cerror { "TLS Client alert received: $alertLevel $alertDescription" }
    }
}
