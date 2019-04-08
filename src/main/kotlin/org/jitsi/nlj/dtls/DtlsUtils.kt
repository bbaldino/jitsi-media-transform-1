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

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDefaultDigestProvider
import org.bouncycastle.operator.bc.BcECContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.math.BigInteger
import java.security.SecureRandom
import java.time.Duration
import java.util.Date
import java.util.NoSuchElementException

class DtlsUtils {
    companion object {
        /**
         * https://tools.ietf.org/html/draft-ietf-rtcweb-security-arch-18#section-6.5
         * rtcweb-security-arch sec. 6.5:
         * All Implementations MUST implement DTLS 1.2 with the
         * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 cipher suite and the P-256
         * curve
         */
        const val SIGNATURE_ALGO: String = "SHA256WITHECDSA"
        /**
         * Finds the first value that appears in both [ours] and [theirs]
         */
        //TODO: typealias for SRTPProtectionProfileArray?
        fun chooseSrtpProtectionProfile(ours: IntArray, theirs: IntArray): Int {
            return try {
                theirs.first(ours::contains)
            } catch (e: NoSuchElementException) {
                //TODO: define a value mapped to 0 which is something like "INVALID_SRTP_PROTECTION_PROFILE"
                0
            }
        }

        /**
         * Computes the fingerprint of a [certificate] using [hashFunction] and return it
         * as a [String]
         */
        private fun computeFingerprint(certificate: org.bouncycastle.asn1.x509.Certificate, hashFunction: String): String {
            val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(hashFunction.toUpperCase())
            val digest = BcDefaultDigestProvider.INSTANCE.get(digAlgId)
            val input: ByteArray = certificate.getEncoded(ASN1Encoding.DER)
            val output = ByteArray(digest.digestSize)

            digest.update(input, 0, input.size)
            digest.doFinal(output, 0)

            return output.toFingerprint()
        }

        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
        /**
         * Helper function to convert a [ByteArray] to a colon-delimited hex string
         */
        private fun ByteArray.toFingerprint(): String {
            val buf = StringBuffer()
            for (i in 0 until size) {
                val octet = get(i).toInt()
                val firstIndex = (octet and 0xF0).ushr(4)
                val secondIndex = octet and 0x0F
                buf.append(HEX_CHARS[firstIndex])
                buf.append(HEX_CHARS[secondIndex])
                if (i < size - 1) {
                    buf.append(":")
                }
            }
            return buf.toString()
        }

        /**
         * Determine and return the hash function (as a [String]) used by this certificate
         */
        private fun org.bouncycastle.asn1.x509.Certificate.getHash(): String {
            val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)

            return BcDefaultDigestProvider.INSTANCE
                .get(digAlgId)
                .algorithmName
                .toLowerCase()
        }

//        private val RSA_KEY_PUBLIC_EXPONENT = BigInteger("10001", 16)
//        private const val RSA_KEY_SIZE = 1024
//        private const val RSA_KEY_SIZE_CERTAINTY = 80
//        /**
//         * Return a pair of RSA private and public keys.
//         */
//        private fun generateRsaKeyPair(): AsymmetricCipherKeyPair {
//            val generator = RSAKeyPairGenerator()
//            generator.init(
//                RSAKeyGenerationParameters(
//                    RSA_KEY_PUBLIC_EXPONENT,
//                    SecureRandom(),
//                    RSA_KEY_SIZE,
//                    RSA_KEY_SIZE_CERTAINTY
//                )
//            )
//            return generator.generateKeyPair()
//        }

        private fun generateEcKeyPair(): AsymmetricCipherKeyPair {
            val generator = ECKeyPairGenerator()
            // "All Implementations MUST implement DTLS 1.2 with the
            //   TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 cipher suite and the P-256
            //   curve"
//            val curveParams = NISTNamedCurves.getByName("P-256")
//            val curveParams = SECNamedCurves.getByName("secp256r1")
//
//
//            generator.init(
//                ECKeyGenerationParameters(
//                    ECDomainParameters(
//                        curveParams.curve,
//                        curveParams.g,
//                        curveParams.n
//                    ),
//                    SecureRandom()
//                )
//            )

            val curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
            generator.init(
                ECKeyGenerationParameters(
                    ECDomainParameters(
                        curveSpec.curve,
                        curveSpec.g,
                        curveSpec.n,
                        curveSpec.h,
                        curveSpec.seed
                    ),
                    SecureRandom()
                )
            )
            return generator.generateKeyPair()
        }

        /**
         * Generates a new subject for a self-signed certificate to be generated by
         * <tt>DtlsControlImpl</tt>.
         *
         * @return an <tt>X500Name</tt> which is to be used as the subject of a
         * self-signed certificate to be generated by <tt>DtlsControlImpl</tt>
         */
        private fun generateCN(appName: String, appVersion: String): X500Name {
            val builder = X500NameBuilder(BCStyle.INSTANCE)
            val rdn = "$appName $appVersion"
            builder.addRDN(BCStyle.CN, rdn)
            return builder.build()
        }

        /**
         * Generates a new self-signed certificate with a specific subject and a
         * specific pair of private and public keys.
         *
         * @param subject the subject (and issuer) of the new certificate to be
         * generated
         * @param keyPair the pair of private and public keys of the certificate to
         * be generated
         * @return a new self-signed certificate with the specified
         * <tt>subject</tt> and <tt>keyPair</tt>
         */
        private fun generateX509Certificate(
            subject: X500Name,
            keyPair: AsymmetricCipherKeyPair,
            signatureAlgo: String = SIGNATURE_ALGO
        ): org.bouncycastle.asn1.x509.Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - Duration.ofDays(1).toMillis())
            val notAfter = Date(now + Duration.ofDays(7).toMillis())

            val certBuilder = X509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                notBefore,
                notAfter,
                subject,
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(keyPair.public)
            )
            val signatureAlgoIdentifier =
                DefaultSignatureAlgorithmIdentifierFinder().find(signatureAlgo)
            val digestAlgoIdentifier =
                DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgoIdentifier)
            //TODO(brian): we take in the signature algorithm (allowing for other values) but
            // hard-code the signer here
            val signer =
                BcECContentSignerBuilder(signatureAlgoIdentifier, digestAlgoIdentifier).build(keyPair.private)

            return certBuilder.build(signer).toASN1Structure()
        }

        /**
         * Generates a new certificate from a new key pair, determines the hash
         * function, and computes the fingerprint.
         *
         * @return CertificateInfo a new certificate generated from a new key pair,
         * its hash function, and fingerprint
         */
        fun generateCertificateInfo(): CertificateInfo {
//            val keyPair = generateRsaKeyPair()
            val keyPair = generateEcKeyPair()

            val x509Certificate =
                generateX509Certificate(generateCN("TODO-APP-NAME", "TODO-APP-VERSION"), keyPair)
            val localFingerprintHashFunction = x509Certificate.getHash()
            val localFingerprint = computeFingerprint(x509Certificate, localFingerprintHashFunction)

            val now = System.currentTimeMillis()
            //TODO(brian): no idea if this is the right way to convert this or not (or if there's a better way to
            // generate the cert entirely?)
//            val tlsCert = Certificate(arrayOf(BcTlsCertificate(BcTlsCrypto(SecureRandom()), byteArrayOf(*x509Certificate.encoded))))

            val tlsCert = Certificate(arrayOf(BcTlsCertificate(BcTlsCrypto(SecureRandom()), x509Certificate)))

            tlsCert.getCertificateAt(0).sigAlgOID

            return CertificateInfo(keyPair, tlsCert, localFingerprintHashFunction, localFingerprint, now)
        }

        /**
         * Verifies and validates a specific certificate against the fingerprints
         * presented by the remote endpoint via the signaling path.
         *
         * @param certificate the certificate to be verified and validated against
         * the fingerprints presented by the remote endpoint via the signaling path
         * @throws [DtlsException] if [certificate] fails validation
         */
        fun verifyAndValidateCertificate(
                certificate: Certificate,
                remoteFingerprints: Map<String, String>) {

            if (certificate.certificateList.isEmpty()) {
                throw DtlsException("No remote fingerprints.")
            }
            for (currCertificate in certificate.certificateList) {
                val x509Cert = org.bouncycastle.asn1.x509.Certificate.getInstance(currCertificate.encoded)
//                verifyAndValidateCertificate(x509Cert, remoteFingerprints)
            }
        }

        /**
         * Verifies and validates a specific certificate against the fingerprints
         * presented by the remote endpoint via the signaling path.
         *
         * @param certificate the certificate to be verified and validated against
         * the fingerprints presented by the remote endpoint via the signaling path.
         * @throws DtlsException if the specified [certificate] failed to verify
         * and validate against the fingerprints presented by the remote endpoint
         * via the signaling path.
         */
        private fun verifyAndValidateCertificate(
            certificate: org.bouncycastle.asn1.x509.Certificate,
            remoteFingerprints: Map<String, String>) {
            // RFC 4572 "Connection-Oriented Media Transport over the Transport
            // Layer Security (TLS) Protocol in the Session Description Protocol
            // (SDP)" defines that "[a] certificate fingerprint MUST be computed
            // using the same one-way hash function as is used in the certificate's
            // signature algorithm."

            val hashFunction = certificate.getHash()

            // As RFC 5763 "Framework for Establishing a Secure Real-time Transport
            // Protocol (SRTP) Security Context Using Datagram Transport Layer
            // Security (DTLS)" states, "the certificate presented during the DTLS
            // handshake MUST match the fingerprint exchanged via the signaling path
            // in the SDP."
            val remoteFingerprint = remoteFingerprints[hashFunction] ?: throw DtlsException("No fingerprint " +
                    "declared over the signaling path with hash function: $hashFunction")

            // TODO(boris) check if the below is still true, and re-introduce the hack if it is.
            // Unfortunately, Firefox does not comply with RFC 5763 at the time
            // of this writing. Its certificate uses SHA-1 and it sends a
            // fingerprint computed with SHA-256. We could, of course, wait for
            // Mozilla to make Firefox compliant. However, we would like to
            // support Firefox in the meantime. That is why we will allow the
            // fingerprint to "upgrade" the hash function of the certificate
            // much like SHA-256 is an "upgrade" of SHA-1.
            /*
            if (remoteFingerprint == null)
            {
                val hashFunctionUpgrade = findHashFunctionUpgrade(hashFunction, remoteFingerprints)

                if (hashFunctionUpgrade != null
                        && !hashFunctionUpgrade.equalsIgnoreCase(hashFunction)) {
                    fingerprint = fingerprints[hashFunctionUpgrade]
                    if (fingerprint != null)
                        hashFunction = hashFunctionUpgrade
                }
            }
            */

            val certificateFingerprint = computeFingerprint(certificate, hashFunction)

            if (remoteFingerprint != certificateFingerprint) {
                throw DtlsException("Fingerprint $remoteFingerprint does not match the $hashFunction-hashed " +
                        "certificate $certificateFingerprint")
            }
        }
    }

    class DtlsException(msg: String) : Exception(msg)
}
