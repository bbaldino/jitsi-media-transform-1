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
package org.jitsi_modified.impl.neomedia.transform.srtp;

import kotlin.*;
import org.bouncycastle.crypto.params.*;
import org.jitsi.bccontrib.params.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.rtp.new_scheme3.rtp.*;
import org.jitsi.rtp.new_scheme3.srtp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import javax.media.Buffer;
import java.nio.*;
import java.util.*;

/**
 * SRTPCryptoContext class is the core class of SRTP implementation. There can
 * be multiple SRTP sources in one SRTP session. And each SRTP stream has a
 * corresponding SRTPCryptoContext object, identified by SSRC. In this way,
 * different sources can be protected independently.
 *
 * SRTPCryptoContext class acts as a manager class and maintains all the
 * information used in SRTP transformation. It is responsible for deriving
 * encryption/salting/authentication keys from master keys. And it will invoke
 * certain class to encrypt/decrypt (transform/reverse transform) RTP packets.
 * It will hold a replay check db and do replay check against incoming packets.
 *
 * Refer to section 3.2 in RFC3711 for detailed description of cryptographic
 * context.
 *
 * Cryptographic related parameters, i.e. encryption mode / authentication mode,
 * master encryption key and master salt key are determined outside the scope of
 * SRTP implementation. They can be assigned manually, or can be assigned
 * automatically using some key management protocol, such as MIKEY (RFC3830),
 * SDES (RFC4568) or Phil Zimmermann's ZRTP protocol (RFC6189).
 *
 * @author Bing SU (nova.su@gmail.com)
 * @author Lyubomir Marinov
 */
public class SRTPCryptoContext
    extends BaseSRTPCryptoContext
{
    /**
     * The name of the <tt>boolean</tt> <tt>ConfigurationService</tt> property
     * which indicates whether protection against replay attacks is to be
     * activated. The default value is <tt>true</tt>.
     */
    public static final String CHECK_REPLAY_PNAME
        = org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext.class.getName() + ".checkReplay";

    /**
     * The indicator which determines whether protection against replay attacks
     * is to be activated. The default value is <tt>true</tt>.
     */
    private static boolean checkReplay = true;

    /**
     * The <tt>Logger</tt> used by the <tt>SRTPCryptoContext</tt> class and its
     * instances to print out debug information.
     */
    private static final Logger logger
        = Logger.getLogger(org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext.class);

    /**
     * The indicator which determines whether the method
     * {@link #readConfigurationServicePropertiesOnce()} is to read the values
     * of certain <tt>ConfigurationService</tt> properties of concern to
     * <tt>SRTPCryptoContext</tt> once during the initialization of the first
     * instance.
     */
    private static boolean readConfigurationServicePropertiesOnce = true;

    /**
     * Reads the values of certain <tt>ConfigurationService</tt> properties of
     * concern to <tt>SRTPCryptoContext</tt> once during the initialization of
     * the first instance.
     */
//    private static synchronized void readConfigurationServicePropertiesOnce()
//    {
//        if (readConfigurationServicePropertiesOnce)
//            readConfigurationServicePropertiesOnce = false;
//        else
//            return;
//
//        ConfigurationService cfg = LibJitsi.getConfigurationService();
//
//        if (cfg != null)
//            checkReplay = cfg.getBoolean(CHECK_REPLAY_PNAME, checkReplay);
//    }

    /**
     * For the receiver only, the rollover counter guessed from the sequence
     * number of the received packet that is currently being processed (i.e. the
     * value is valid during the execution of
     * {@link #reverseTransformPacket(SrtpPacket)} only.) RFC 3711 refers to it
     * by the name <tt>v</tt>.
     */
    private int guessedROC;

    /**
     * Key Derivation Rate, used to derive session keys from master keys
     */
    private final long keyDerivationRate;

    /**
     * RFC 3711: a 32-bit unsigned rollover counter (ROC), which records how
     * many times the 16-bit RTP sequence number has been reset to zero after
     * passing through 65,535.  Unlike the sequence number (SEQ), which SRTP
     * extracts from the RTP packet header, the ROC is maintained by SRTP as
     * described in Section 3.3.1.
     */
    private int roc;

    /**
     * RFC 3711: for the receiver only, a 16-bit sequence number <tt>s_l</tt>,
     * which can be thought of as the highest received RTP sequence number (see
     * Section 3.3.1 for its handling), which SHOULD be authenticated since
     * message authentication is RECOMMENDED.
     */
    private int s_l = 0;

    /**
     * The indicator which determines whether this instance is used by an SRTP
     * sender (<tt>true</tt>) or receiver (<tt>false</tt>).
     */
    private final boolean sender;

    /**
     * The indicator which determines whether {@link #s_l} has seen set i.e.
     * appropriately initialized.
     */
    private boolean seqNumSet = false;

    /**
     * Constructs an empty SRTPCryptoContext using ssrc. The other parameters
     * are set to default null value.
     *
     * @param sender <tt>true</tt> if the new instance is to be used by an SRTP
     * sender; <tt>false</tt> if the new instance is to be used by an SRTP
     * receiver
     * @param ssrc SSRC of this SRTPCryptoContext
     */
    public SRTPCryptoContext(boolean sender, int ssrc)
    {
        super(ssrc);

        this.sender = sender;

        keyDerivationRate = 0;
        roc = 0;
    }

    /**
     * Constructs a normal SRTPCryptoContext based on the given parameters.
     *
     * @param sender <tt>true</tt> if the new instance is to be used by an SRTP
     * sender; <tt>false</tt> if the new instance is to be used by an SRTP
     * receiver
     * @param ssrc the RTP SSRC that this SRTP cryptographic context protects.
     * @param roc the initial Roll-Over-Counter according to RFC 3711. These
     * are the upper 32 bit of the overall 48 bit SRTP packet index. Refer to
     * chapter 3.2.1 of the RFC.
     * @param keyDerivationRate the key derivation rate defines when to
     * recompute the SRTP session keys. Refer to chapter 4.3.1 in the RFC.
     * @param masterK byte array holding the master key for this SRTP
     * cryptographic context. Refer to chapter 3.2.1 of the RFC about the role
     * of the master key.
     * @param masterS byte array holding the master salt for this SRTP
     * cryptographic context. It is used to computer the initialization vector
     * that in turn is input to compute the session key, session authentication
     * key and the session salt.
     * @param policy SRTP policy for this SRTP cryptographic context, defined
     * the encryption algorithm, the authentication algorithm, etc
     */
    @SuppressWarnings("fallthrough")
    public SRTPCryptoContext(
            boolean sender,
            int ssrc,
            int roc,
            long keyDerivationRate,
            byte[] masterK,
            byte[] masterS,
            SRTPPolicy policy)
    {
        super(ssrc, masterK, masterS, policy);
        logger.debug("BRIAN: creating srtp crypto context " + System.identityHashCode(this) + " with: " +
                "\nsender? " + sender +
                "\nssrc " + (ssrc & 0xFFFF_FFFFL) +
                "\nroc: " + roc +
                "\nkeyDerivationRate: " + keyDerivationRate +
                "\nmasterK: " + ByteBufferKt.toHex(ByteBuffer.wrap(masterK)) +
                "\nmasterS: " + ByteBufferKt.toHex(ByteBuffer.wrap(masterS)) +
                "\npolicy: " + policy.toString());

        this.sender = sender;
        this.roc = roc;
        this.keyDerivationRate = keyDerivationRate;

//        readConfigurationServicePropertiesOnce();
    }


    /**
     * Authenticates a specific <tt>SrtpPacket</tt> if the <tt>policy</tt> of
     * this <tt>SRTPCryptoContext</tt> specifies that authentication is to be
     * performed.
     *
     * @param srtpPacket the <tt>SrtpPacket</tt> to acuthenticate
     * @return <tt>true</tt> if the <tt>policy</tt> of this
     * <tt>SRTPCryptoContext</tt> specifies that authentication is to not be
     * performed or <tt>pkt</tt> was successfully authenticated; otherwise,
     * <tt>false</tt>
     */
    private boolean authenticatePacket(SrtpPacket srtpPacket)
    {
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
        {
            int tagLength = policy.getAuthTagLength();

            // get original authentication and store in tempStore
            ByteBuffer authTag = srtpPacket.getAuthTag(tagLength);
            srtpPacket.removeAuthTag(tagLength);
            authTag.get(tempStore, 0, tagLength);

            // save computed authentication in tagStore
            authenticatePacketHMAC(srtpPacket.getBuffer(), guessedROC);

            // compare authentication tags using constant time comparison
            int nonEqual = 0;
            for (int i = 0; i < tagLength; i++)
            {
                nonEqual |= (tempStore[i] ^ tagStore[i]);
            }
            if (nonEqual != 0)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a packet is a replayed based on its sequence number. The method
     * supports a 64 packet history relative the the specified sequence number.
     * The sequence number is guaranteed to be real (i.e. not faked) through
     * authentication.
     *
     * @param seqNo sequence number of the packet
     * @param guessedIndex guessed ROC
     * @return <tt>true</tt> if the specified sequence number indicates that the
     * packet is not a replayed one; <tt>false</tt>, otherwise
     */
    boolean checkReplay(int seqNo, long guessedIndex)
    {
        if (!checkReplay)
            return true;

        // Compute the index of the previously received packet and its delta to
        // the newly received packet.
        long localIndex = (((long) roc) << 16) | s_l;
        long delta = guessedIndex - localIndex;

        if (delta > 0)
        {
            return true; // Packet not received yet.
        }
        else if (-delta > REPLAY_WINDOW_SIZE)
        {
            if (sender)
            {
                logger.error(
                        "Discarding RTP packet with sequence number " + seqNo
                            + ", SSRC " + Long.toString(0xFFFFFFFFL & ssrc)
                            + " because it is outside the replay window! (roc "
                            + roc + ", s_l " + s_l + ", guessedROC "
                            + guessedROC);
            }
            return false; // Packet too old.
        }
        else if (((replayWindow >> (-delta)) & 0x1) != 0)
        {
            if (sender)
            {
                logger.error(
                        "Discarding RTP packet with sequence number " + seqNo
                            + ", SSRC " + Long.toString(0xFFFFFFFFL & ssrc)
                            + " because it has been received already! (roc "
                            + roc + ", s_l " + s_l + ", guessedROC "
                            + guessedROC);
            }
            return false; // Packet received already!
        }
        else
        {
            return true; // Packet not received yet.
        }
    }

    /**
     * Computes the initialization vector, used later by encryption algorithms,
     * based on the label, the packet index, key derivation rate and master salt
     * key.
     *
     * @param label label specified for each type of iv
     * @param index 48bit RTP packet index
     */
    private void computeIv(long label, long index)
    {
        long key_id;

        if (keyDerivationRate == 0)
        {
            key_id = label << 48;
        }
        else
        {
            key_id = ((label << 48) | (index / keyDerivationRate));
        }
        for (int i = 0; i < 7; i++)
        {
            ivStore[i] = masterSalt[i];
        }
        for (int i = 7; i < 14; i++)
        {
            ivStore[i] = (byte)
                (
                    (byte) (0xFF & (key_id >> (8 * (13 - i))))
                    ^
                    masterSalt[i]
                );
        }
        ivStore[14] = ivStore[15] = 0;
    }

    /**
     * Derives a new SRTPCryptoContext for use with a new SSRC. The method
     * returns a new SRTPCryptoContext initialized with the data of this
     * SRTPCryptoContext. Replacing the SSRC, Roll-over-Counter, and the key
     * derivation rate the application cab use this SRTPCryptoContext to
     * encrypt/decrypt a new stream (Synchronization source) inside one RTP
     * session. Before the application can use this SRTPCryptoContext it must
     * call the deriveSrtpKeys method.
     *
     * @param ssrc The SSRC for this context
     * @param roc The Roll-Over-Counter for this context
     * @param deriveRate The key derivation rate for this context
     * @return a new SRTPCryptoContext with all relevant data set.
     */
    public SRTPCryptoContext deriveContext(int ssrc, int roc, long deriveRate)
    {
        return
            new SRTPCryptoContext(
                    sender,
                    ssrc,
                    roc,
                    deriveRate,
                    masterKey,
                    masterSalt,
                    policy);
    }

    /**
     * Derives the srtp session keys from the master key
     *
     * @param index the 48 bit SRTP packet index
     */
    synchronized public void deriveSrtpKeys(long index)
    {
        // compute the session encryption key
        computeIv(0x00, index);

        cipherCtr.init(masterKey);
        Arrays.fill(masterKey, (byte) 0);

        Arrays.fill(encKey, (byte) 0);
        cipherCtr.process(encKey, 0, policy.getEncKeyLength(), ivStore);

        // compute the session authentication key
        if (authKey != null)
        {
            computeIv(0x01, index);
            Arrays.fill(authKey, (byte) 0);
            cipherCtr.process(authKey, 0, policy.getAuthKeyLength(), ivStore);

            switch (policy.getAuthType())
            {
            case SRTPPolicy.HMACSHA1_AUTHENTICATION:
                mac.init(new KeyParameter(authKey));
                break;

            case SRTPPolicy.SKEIN_AUTHENTICATION:
                // Skein MAC uses number of bits as MAC size, not just bytes
                mac.init(
                        new ParametersForSkein(
                                new KeyParameter(authKey),
                                ParametersForSkein.Skein512,
                                tagStore.length * 8));
                break;
            }

            Arrays.fill(authKey, (byte) 0);
        }

        // compute the session salt
        computeIv(0x02, index);
        Arrays.fill(saltKey, (byte) 0);
        cipherCtr.process(saltKey, 0, policy.getSaltKeyLength(), ivStore);
        Arrays.fill(masterSalt, (byte) 0);

        // As last step: initialize cipher with derived encryption key.
        if (cipherF8 != null)
            cipherF8.init(encKey, saltKey);
        cipherCtr.init(encKey);
        Arrays.fill(encKey, (byte) 0);
    }

    /**
     * For the receiver only, determines/guesses the SRTP index of a received
     * SRTP packet with a specific sequence number.
     *
     * @param seqNo the sequence number of the received SRTP packet
     * @return the SRTP index of the received SRTP packet with the specified
     * <tt>seqNo</tt>
     */
    private long guessIndex(int seqNo)
    {
        if (s_l < 32768)
        {
            if (seqNo - s_l > 32768)
                guessedROC = roc - 1;
            else
                guessedROC = roc;
        }
        else
        {
            if (s_l - 32768 > seqNo)
                guessedROC = roc + 1;
            else
                guessedROC = roc;
        }

        return (((long) guessedROC) << 16) | seqNo;
    }


    /**
     * Performs Counter Mode AES encryption/decryption
     *
     * @param ssrc
     * @param seqNum
     * @param payload
     */
    private void processPacketAESCM(int ssrc, int seqNum, ByteBuffer payload)
    {
        long index = (((long) guessedROC) << 16) | seqNum;

        // byte[] iv = new byte[16];
        ivStore[0] = saltKey[0];
        ivStore[1] = saltKey[1];
        ivStore[2] = saltKey[2];
        ivStore[3] = saltKey[3];

        int i;

        for (i = 4; i < 8; i++)
        {
            ivStore[i] = (byte)
                    (
                            (0xFF & (ssrc >> ((7 - i) * 8)))
                                    ^
                                    saltKey[i]
                    );
        }

        for (i = 8; i < 14; i++)
        {
            ivStore[i] = (byte)
                    (
                            (0xFF & (byte) (index >> ((13 - i) * 8)))
                                    ^
                                    saltKey[i]
                    );
        }

        ivStore[14] = ivStore[15] = 0;

        cipherCtr.process(payload.array(), payload.arrayOffset(), payload.limit(), ivStore);
    }

    /**
     * Performs F8 Mode AES encryption/decryption
     *
     * @param header
     * @param payload
     */
    private void processPacketAESF8(ByteBuffer header, ByteBuffer payload)
    {
        // 11 bytes of the RTP header are the 11 bytes of the iv
        // the first byte of the RTP header is not used.
        System.arraycopy(header.array(), header.arrayOffset(), ivStore, 0, 12);
        ivStore[0] = 0;

        // set the ROC in network order into IV
        int roc = guessedROC;

        ivStore[12] = (byte) (roc >> 24);
        ivStore[13] = (byte) (roc >> 16);
        ivStore[14] = (byte) (roc >> 8);
        ivStore[15] = (byte) roc;

        cipherF8.process(payload.array(), payload.arrayOffset(), payload.limit(), ivStore);
    }

    /**
     * Transforms an SRTP packet into an RTP packet. The method is called when
     * an SRTP packet is received. Operations done by the this operation
     * include: authentication check, packet replay check and decryption. Both
     * encryption and authentication functionality can be turned off as long as
     * the SRTPPolicy used in this SRTPCryptoContext is requires no encryption
     * and no authentication. Then the packet will be sent out untouched.
     * However, this is not encouraged. If no SRTP feature is enabled, then we
     * shall not use SRTP TransformConnector. We should use the original method
     * (RTPManager managed transportation) instead.
     *
     * @param srtpPacket the RTP packet that is just received
     * @return <tt>true</tt> if the packet can be accepted; <tt>false</tt> if
     * the packet failed authentication or failed replay check
     */
    synchronized public RtpPacket reverseTransformPacket(SrtpPacket srtpPacket)
    {
        int seqNum = srtpPacket.getHeader().getSequenceNumber();
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    System.identityHashCode(this) + " Reverse transform for SSRC " +
                            (this.ssrc & 0xFFFF_FFFFL)
                        + " SeqNo=" + seqNum
                        + " s_l=" + s_l
                        + " seqNumSet=" + seqNumSet
                        + " guessedROC=" + guessedROC
                        + " roc=" + roc);
        }


        // Whether s_l was initialized while processing this packet.
        boolean seqNumWasJustSet = false;
        if (!seqNumSet)
        {
            seqNumSet = true;
            s_l = seqNum;
            seqNumWasJustSet = true;
        }

        // Guess the SRTP index (48 bit), see RFC 3711, 3.3.1
        // Stores the guessed rollover counter (ROC) in this.guessedROC.
        long guessedIndex = guessIndex(seqNum);

        // Replay control
        if (checkReplay(seqNum, guessedIndex))
        {
            // Authenticate the packet.
            if (authenticatePacket(srtpPacket))
            {
                // If a RawPacket is flagged with Buffer.FLAG_DISCARD, then it
                // should have been discarded earlier. Anyway, at least skip its
                // decrypting. We flag a RawPacket with Buffer.FLAG_SILENCE when
                // we want to ignore its payload. In the context of SRTP, we
                // want to skip its decrypting.
                //TODO(brian): bring these back if we need them here.  if so, that'd
                // also mean we want PacketInfo here (since that is where we'd store
                // this info)
//                if ((pkt.getFlags()
//                            & (Buffer.FLAG_DISCARD | Buffer.FLAG_SILENCE))
//                        == 0)
                {
                    srtpPacket.modifyPayload(payload -> {
                        switch (policy.getEncType())
                        {
                            // Decrypt the packet using Counter Mode encryption.
                            case SRTPPolicy.AESCM_ENCRYPTION:
                            case SRTPPolicy.TWOFISH_ENCRYPTION:
                                processPacketAESCM((int)srtpPacket.getHeader().getSsrc(), seqNum, payload);
                                break;

                            // Decrypt the packet using F8 Mode encryption.
                            case SRTPPolicy.AESF8_ENCRYPTION:
                            case SRTPPolicy.TWOFISHF8_ENCRYPTION:
                                processPacketAESF8(srtpPacket.getHeader().getBuffer(), payload);
                                break;
                        }

                        return Unit.INSTANCE;
                    });
                }

                // Update the rollover counter and highest sequence number if
                // necessary.
                update(seqNum, guessedIndex);
                //TODO(brian): re-use buffer
                return new RtpPacket(srtpPacket.getHeader(), srtpPacket.getPayload(), null);
            }
            else
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("SRTP auth failed for SSRC " + ssrc);
                }
                if (seqNumWasJustSet)
                {
                    // We set the initial value of s_l as a result of processing this
                    // packet, but the packet failed to authenticate. We shouldn't
                    // update our state based on an untrusted packet, so we revert
                    // seqNumSet.
                    seqNumSet = false;
                    s_l = 0;
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Transforms an RTP packet into an SRTP packet. The method is called when a
     * normal RTP packet ready to be sent. Operations done by the transformation
     * may include: encryption, using either Counter Mode encryption, or F8 Mode
     * encryption, adding authentication tag, currently HMC SHA1 method. Both
     * encryption and authentication functionality can be turned off as long as
     * the SRTPPolicy used in this SRTPCryptoContext is requires no encryption
     * and no authentication. Then the packet will be sent out untouched.
     * However, this is not encouraged. If no SRTP feature is enabled, then we
     * shall not use SRTP TransformConnector. We should use the original method
     * (RTPManager managed transportation) instead.
     *
     * @param rtpPacket the RTP packet that is going to be sent out
     */
    synchronized public SrtpPacket transformPacket(RtpPacket rtpPacket)
    {
        int seqNum = rtpPacket.getHeader().getSequenceNumber();

        logger.debug(
                System.identityHashCode(this) + " Transform for SSRC " +
                        (this.ssrc & 0xFFFF_FFFFL)
                        + " SeqNo=" + seqNum
                        + " s_l=" + s_l
                        + " seqNumSet=" + seqNumSet
                        + " guessedROC=" + guessedROC
                        + " roc=" + roc);
        if (!seqNumSet)
        {
            seqNumSet = true;
            s_l = seqNum;
        }

        // Guess the SRTP index (48 bit), see RFC 3711, 3.3.1
        // Stores the guessed ROC in this.guessedROC
        long guessedIndex = guessIndex(seqNum);

        /*
         * XXX The invocation of the checkReplay method here is not meant as
         * replay protection but as a consistency check of our implementation.
         */
        if (!checkReplay(seqNum, guessedIndex))
        {
            return null;
        }

//        ByteBuffer packetBuf = rtpPacket.getBuffer();
//        ByteBuffer payload = rtpPacket.getPayload();
        rtpPacket.modifyPayload(payload -> {
            switch (policy.getEncType())
            {
                // Encrypt the packet using Counter Mode encryption.
                case SRTPPolicy.AESCM_ENCRYPTION:
                case SRTPPolicy.TWOFISH_ENCRYPTION:
                    processPacketAESCM((int)rtpPacket.getHeader().getSsrc(), seqNum, payload);
                    break;

                // Encrypt the packet using F8 Mode encryption.
                case SRTPPolicy.AESF8_ENCRYPTION:
                case SRTPPolicy.TWOFISHF8_ENCRYPTION:
                    //TODO(brian)
//            ByteBuffer header = rtpPacket.getHeader().getBuffer();
//            processPacketAESF8(header, payload);
//            rtpPacket.getBuffer();
                    break;
            }
            return Unit.INSTANCE;
        });
        //TODO(brian): so this is a bit tricky.  with RtpPacket specifically, it's
        // possible that the ByteBuffer used to hold the payload is different from the one
        // holding its header.  this means that the packetBuf we hold, above,
        // won't reflect the newly-updated payload buf and we need to get
        // them in sync again by calling rtpPacket.getBuffer()
        // We could also add a new SrtpPacket ctor which allowed taking in
        // separate header and payload buffers, which might make sense?
//        rtpPacket.getBuffer();
//        SrtpPacket srtpPacket = new SrtpPacket(packetBuf);
        //TODO(brian): pass along buffer
        SrtpPacket srtpPacket = new SrtpPacket(rtpPacket.getHeader(), rtpPacket.getPayload(), null);

        /* Authenticate the packet. */
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION)
        {
            authenticatePacketHMAC(srtpPacket.getBuffer(), guessedROC);
            srtpPacket.addAuthTag(ByteBuffer.wrap(tagStore, 0, policy.getAuthTagLength()));
        }

        // Update the ROC if necessary.
        update(seqNum, guessedIndex);

        return srtpPacket;
    }

    /**
     * For the receiver only, updates the rollover counter (i.e. {@link #roc})
     * and highest sequence number (i.e. {@link #s_l}) in this cryptographic
     * context using the SRTP/packet index calculated by
     * {@link #guessIndex(int)} and updates the replay list (i.e.
     * {@link #replayWindow}). This method is called after all checks were
     * successful.
     *
     * @param seqNo the sequence number of the accepted SRTP packet
     * @param guessedIndex the SRTP index of the accepted SRTP packet calculated
     * by <tt>guessIndex(int)</tt>
     */
    private void update(int seqNo, long guessedIndex)
    {
        long delta = guessedIndex - ((((long) roc) << 16) | s_l);

        /* Update the replay bit mask. */
        if (delta > 0)
        {
            replayWindow <<= delta;
            replayWindow |= 1;
        }
        else
        {
            replayWindow |= (1 << -delta);
        }

        if (guessedROC == roc)
        {
            if (seqNo > s_l)
                s_l = seqNo & 0xffff;
        }
        else if (guessedROC == (roc + 1))
        {
            s_l = seqNo & 0xffff;
            roc = guessedROC;
        }
    }

    @Override
    public String toString()
    {
        return "" +
                this.hashCode() +
                " ssrc: " + ssrc +
                " masterK: " + ByteBufferKt.toHex(ByteBuffer.wrap(masterKey)) +
                " masterS: " + ByteBufferKt.toHex(ByteBuffer.wrap(masterSalt)) +
                " encKey: " + ByteBufferKt.toHex(ByteBuffer.wrap(encKey)) +
                " saltKey: " + ByteBufferKt.toHex(ByteBuffer.wrap(saltKey)) +
                " policy: " + policy +
                " sender? " + sender +
                " roc: " + roc +
                " key derivation rate: " + keyDerivationRate;
    }
}