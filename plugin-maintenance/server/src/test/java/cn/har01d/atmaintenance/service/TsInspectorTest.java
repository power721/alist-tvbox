package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static cn.har01d.atmaintenance.service.TsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TsInspectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixtureCrcMatchesPublishedMpeg2CheckVectorAndHasZeroSectionResidue() {
        byte[] vector = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x0376e6e7L, crc32Mpeg2(vector));
        assertEquals(0, crc32Mpeg2(appendCrc(vector)));
        assertEquals(0, crc32Mpeg2(pat(PROGRAM, PMT_PID)));
        assertEquals(0, crc32Mpeg2(pmt(PROGRAM, new byte[0], elementary(0x1b, VIDEO_PID))));
    }

    @Test
    void associatesPatPmtAndH264PesWithoutClaimingCodecDecode() throws Exception {
        ObjectNode result = inspect(stream(), false);
        assertEquals(188, result.path("packetBytes").asInt());
        assertEquals(3, result.path("observedPackets").asInt());
        assertEquals(PROGRAM, result.path("programNumber").asInt());
        assertEquals(PMT_PID, result.path("pmtPid").asInt());
        assertEquals(VIDEO_PID, result.path("pcrPid").asInt());
        assertTrue(result.path("psiCrcChecked").asBoolean());
        assertFalse(result.path("codecDecoded").asBoolean());
        assertEquals("single_program_psi_and_pes_prefix", result.path("scope").asText());
        assertEquals(1, result.path("mediaStreamsWithPesPrefix").asInt());
        assertEquals(VIDEO_PID, result.path("streams").get(0).path("pid").asInt());
        assertEquals(0x1b, result.path("streams").get(0).path("streamType").asInt());
        assertTrue(result.path("streams").get(0).path("pesPrefixObserved").asBoolean());
    }

    @Test
    void recognizesAacPesAndPermitsUnknownNonMediaDeclarations() throws Exception {
        byte[] pmt = pmt(PROGRAM, new byte[0], elementary(0x0f, VIDEO_PID),
                elementary(0x06, VIDEO_PID + 1));
        var result = inspect(transport(pat(PROGRAM, PMT_PID), pmt, pesPrefix(0xc0)), false);
        assertEquals(2, result.path("streams").size());
        assertEquals(1, result.path("mediaStreamsWithPesPrefix").asInt());
        assertFalse(result.path("streams").get(1).path("recognizedMediaType").asBoolean());
    }

    @Test
    void missingPatPmtAndPesHaveSpecificCompleteSampleReasons() {
        byte[][] samples = {
                concat(nullPacket(0), pmtPacket(), pesPacket()),
                concat(patPacket(), nullPacket(0), pesPacket()),
                concat(patPacket(), pmtPacket(), nullPacket(0))
        };
        String[] reasons = {"MEDIA_TS_PAT_MISSING", "MEDIA_TS_PMT_MISSING", "MEDIA_TS_PES_MISSING"};
        for (int index = 0; index < samples.length; index++) {
            invalid(reasons[index], samples[index], false);
            invalid("MEDIA_TS_PREFIX_INCOMPLETE", samples[index], true);
        }
    }

    @Test
    void shortAndUnalignedSamplesDistinguishCompleteBytesFromTruncatedPrefix() throws Exception {
        invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID", new byte[187], false);
        invalid("MEDIA_TS_PREFIX_INCOMPLETE", new byte[187], true);
        byte[] prefix = concat(stream(), new byte[]{0x47, 0, 0, 0, 0, 0, 0});
        invalid("MEDIA_TS_PACKET_ALIGNMENT_INVALID", prefix, false);
        var result = inspect(prefix, true);
        assertTrue(result.path("prefixOnly").asBoolean());
        assertEquals(3, result.path("observedPackets").asInt());
        assertEquals(7, result.path("trailingBytesNotInspected").asInt());
    }

    @Test
    void checksPacketDamageBeyondTheFirstSixteenPackets() throws Exception {
        byte[][] packets = new byte[19][];
        packets[0] = patPacket();
        packets[1] = pmtPacket();
        packets[2] = pesPacket();
        for (int index = 3; index < packets.length; index++) packets[index] = nullPacket(index);
        assertEquals(19, inspect(concat(packets), false).path("observedPackets").asInt());
        packets[17][0] = 0;
        invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID", concat(packets), false);
    }

    @Test
    void rejectsTransportErrorIndicatorAndReservedAdaptationControl() {
        byte[] tei = stream();
        tei[2 * 188 + 1] |= (byte) 0x80;
        invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID", tei, false);
        byte[] control = stream();
        control[2 * 188 + 3] = 0;
        invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID", control, false);
    }

    @Test
    void marksScrambledTransportAsUnsupportedInsteadOfSuccessfulMedia() {
        byte[] scrambled = stream();
        scrambled[2 * 188 + 3] |= (byte) 0x80;
        unsupported("MEDIA_TS_SCRAMBLED_UNSUPPORTED", scrambled);
    }

    @Test
    void rejectsAdaptationLengthsThatCrossPacketOrEliminateRequiredPayload() {
        for (int length : new int[]{183, 184, 255}) {
            byte[] sample = stream();
            sample[4] = (byte) length;
            invalid("MEDIA_TS_ADAPTATION_INVALID", sample, false);
        }
        byte[] adaptationOnly = stream();
        adaptationOnly[3] = 0x20;
        adaptationOnly[4] = 0;
        invalid("MEDIA_TS_ADAPTATION_INVALID", adaptationOnly, false);
    }

    @Test
    void rejectsBadPatAndPmtCrcEvenWhenAllPacketHeadersAreValid() {
        byte[] badPat = pat(PROGRAM, PMT_PID);
        badPat[badPat.length - 1] ^= 1;
        invalid("MEDIA_TS_PSI_CRC_INVALID", transport(badPat, standardPmt(), pesPrefix(0xe0)), false);
        byte[] badPmt = standardPmt();
        badPmt[badPmt.length - 1] ^= 1;
        invalid("MEDIA_TS_PSI_CRC_INVALID", transport(pat(PROGRAM, PMT_PID), badPmt, pesPrefix(0xe0)), false);
    }

    @Test
    void rejectsPatWithoutProgramAndWithReservedPmtPid() {
        for (byte[] pat : new byte[][]{
                pat(), pat(0, 0x10), pat(0, 0, PROGRAM, PMT_PID), pat(PROGRAM, 0), pat(PROGRAM, 0x1fff)
        }) {
            invalid("MEDIA_TS_PAT_INVALID", transport(pat, standardPmt(), pesPrefix(0xe0)), false);
        }
    }

    @Test
    void validMultiProgramPatIsAnUnsupportedFormat() {
        unsupported("MEDIA_TS_MULTIPLE_PROGRAMS_UNSUPPORTED",
                transport(pat(PROGRAM, PMT_PID, 2, PMT_PID + 2), standardPmt(), pesPrefix(0xe0)));
    }

    @Test
    void rejectsPmtProgramMismatchAndReservedOrDuplicateElementaryPids() {
        byte[][] invalidPmts = {
                pmt(PROGRAM + 1, new byte[0], elementary(0x1b, VIDEO_PID)),
                pmt(PROGRAM, new byte[0], elementary(0x1b, 0)),
                pmt(PROGRAM, new byte[0], elementary(0x1b, 0x1fff)),
                pmt(PROGRAM, new byte[0], elementary(0x1b, PMT_PID)),
                pmt(PROGRAM, new byte[0], elementary(0x1b, VIDEO_PID), elementary(0x0f, VIDEO_PID)),
                pmt(PROGRAM, new byte[0])
        };
        for (byte[] pmt : invalidPmts) {
            invalid("MEDIA_TS_PMT_INVALID", transport(pat(PROGRAM, PMT_PID), pmt, pesPrefix(0xe0)), false);
        }
        byte[] badPcr = standardPmt();
        badPcr[8] = (byte) 0xe0;
        badPcr[9] = 0;
        invalid("MEDIA_TS_PMT_INVALID", transport(pat(PROGRAM, PMT_PID), renewCrc(badPcr), pesPrefix(0xe0)), false);
    }

    @Test
    void checksDescriptorBoundariesAtBothProgramAndElementaryStreamLevels() throws Exception {
        byte[] valid = pmt(PROGRAM, new byte[]{0x52, 1, 3},
                elementary(0x1b, VIDEO_PID, (byte) 0x52, (byte) 1, (byte) 4));
        assertEquals(1, inspect(transport(pat(PROGRAM, PMT_PID), valid, pesPrefix(0xe0)), false)
                .path("mediaStreamsWithPesPrefix").asInt());
        for (byte[] descriptors : new byte[][]{{0x52}, {0x52, 2, 3}}) {
            invalid("MEDIA_TS_PMT_INVALID", transport(pat(PROGRAM, PMT_PID),
                    pmt(PROGRAM, descriptors, elementary(0x1b, VIDEO_PID)), pesPrefix(0xe0)), false);
            invalid("MEDIA_TS_PMT_INVALID", transport(pat(PROGRAM, PMT_PID),
                    pmt(PROGRAM, new byte[0], elementary(0x1b, VIDEO_PID, descriptors)), pesPrefix(0xe0)), false);
        }
        byte[] excessive = standardPmt();
        excessive[10] = (byte) 0xff;
        excessive[11] = (byte) 0xff;
        invalid("MEDIA_TS_PMT_INVALID", transport(pat(PROGRAM, PMT_PID), renewCrc(excessive), pesPrefix(0xe0)), false);
    }

    @Test
    void unknownOnlyStreamTypesAreUnsupported() {
        unsupported("MEDIA_TS_STREAM_TYPE_UNSUPPORTED", transport(pat(PROGRAM, PMT_PID),
                pmt(PROGRAM, new byte[0], elementary(0x06, VIDEO_PID)), pesPrefix(0xe0)));
    }

    @Test
    void multiSectionPatAndPmtAreUnsupportedWithValidCrc() {
        byte[] pat = pat(PROGRAM, PMT_PID);
        pat[7] = 1;
        unsupported("MEDIA_TS_MULTISECTION_UNSUPPORTED", transport(renewCrc(pat), standardPmt(), pesPrefix(0xe0)));
        byte[] pmt = standardPmt();
        pmt[6] = 1;
        pmt[7] = 1;
        unsupported("MEDIA_TS_MULTISECTION_UNSUPPORTED", transport(pat(PROGRAM, PMT_PID), renewCrc(pmt), pesPrefix(0xe0)));
    }

    @Test
    void changingCurrentTableIsUnsupportedButIdenticalRepeatedSectionsAreValid() throws Exception {
        byte[] first = patPacket();
        byte[] repeat = psiPacket(0, 1, pat(PROGRAM, PMT_PID));
        assertEquals(4, inspect(concat(first, repeat, pmtPacket(), pesPacket()), false)
                .path("observedPackets").asInt());
        byte[] changed = pat(PROGRAM, PMT_PID);
        changed[5] = (byte) 0xc3;
        unsupported("MEDIA_TS_TABLE_CHANGE_UNSUPPORTED",
                concat(first, psiPacket(0, 1, renewCrc(changed)), pmtPacket(), pesPacket()));
    }

    @Test
    void nextTableDoesNotCountAsTheCurrentPat() {
        byte[] next = pat(PROGRAM, PMT_PID);
        next[5] = (byte) 0xc0;
        invalid("MEDIA_TS_PAT_MISSING", transport(renewCrc(next), standardPmt(), pesPrefix(0xe0)), false);
    }

    @Test
    void reassemblesPatAndPmtAcrossHeaderAndBodySplits() throws Exception {
        byte[] pat = pat(PROGRAM, PMT_PID);
        for (int split : new int[]{1, 2, 3, 8, pat.length - 1}) {
            byte[] first = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, split)));
            byte[] second = packet(0, false, 1, Arrays.copyOfRange(pat, split, pat.length));
            assertEquals(PROGRAM, inspect(concat(first, second, pmtPacket(), pesPacket()), false)
                    .path("programNumber").asInt(), "PAT split " + split);
        }
        byte[] pmt = standardPmt();
        byte[] first = packet(PMT_PID, true, 0, concat(new byte[]{0}, Arrays.copyOf(pmt, 2)));
        byte[] second = packet(PMT_PID, false, 1, Arrays.copyOfRange(pmt, 2, pmt.length));
        assertTrue(inspect(concat(patPacket(), first, second, pesPacket()), false)
                .path("psiCrcChecked").asBoolean());
    }

    @Test
    void pointerCanFinishPreviousSectionAndStartAnIdenticalNextSection() throws Exception {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] remaining = Arrays.copyOfRange(pat, 8, pat.length);
        byte[] first = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, 8)));
        byte[] second = packet(0, true, 1, concat(new byte[]{(byte) remaining.length}, remaining, pat));
        assertEquals(PROGRAM, inspect(concat(first, second, pmtPacket(), pesPacket()), false)
                .path("programNumber").asInt());
    }

    @Test
    void rejectsPointerOutsidePayloadAndPointerThatCannotFinishPendingSection() {
        byte[] outside = packet(0, true, 0, new byte[]{2, 0, 0});
        invalid("MEDIA_TS_PSI_POINTER_INVALID", concat(outside, pmtPacket(), pesPacket()), false);
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] first = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, 8)));
        byte[] premature = packet(0, true, 1, concat(new byte[]{1, pat[8]}, pat));
        invalid("MEDIA_TS_PSI_INVALID", concat(first, premature, pmtPacket(), pesPacket()), false);
    }

    @Test
    void initialPointerMaySkipTheUnknownTailOfAnEarlierSection() throws Exception {
        byte[] initial = packet(0, true, 0,
                concat(new byte[]{3, 0x12, 0x34, 0x56}, pat(PROGRAM, PMT_PID)));
        assertEquals(PROGRAM, inspect(concat(initial, pmtPacket(), pesPacket()), false)
                .path("programNumber").asInt());
    }

    @Test
    void pusiPointerMustIdentifyANewSectionRatherThanOnlyStuffing() {
        byte[] stuffingOnly = packet(0, true, 1, new byte[]{0, (byte) 0xff, (byte) 0xff});
        invalid("MEDIA_TS_PSI_POINTER_INVALID", concat(patPacket(), stuffingOnly, pmtPacket(), pesPacket()), false);
        invalid("MEDIA_TS_PSI_POINTER_INVALID", concat(patPacket(), stuffingOnly, pmtPacket(), pesPacket()), true);
    }

    @Test
    void trailingIncompleteSectionCannotHideBehindAnEarlierValidTable() {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] pending = packet(0, true, 1, concat(new byte[]{0}, Arrays.copyOf(pat, 2)));
        byte[] sample = concat(patPacket(), pmtPacket(), pesPacket(), pending);
        invalid("MEDIA_TS_PSI_INCOMPLETE", sample, false);
        invalid("MEDIA_TS_PREFIX_INCOMPLETE", sample, true);
    }

    @Test
    void trailingIncompleteSectionDoesNotHideObservedCrcDamage() {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] pending = packet(0, true, 1, concat(new byte[]{0}, Arrays.copyOf(pat, 2)));
        pat[pat.length - 1] ^= 1;
        byte[] sample = concat(psiPacket(0, 0, pat), pmtPacket(), pesPacket(), pending);
        invalid("MEDIA_TS_PSI_CRC_INVALID", sample, false);
        invalid("MEDIA_TS_PSI_CRC_INVALID", sample, true);
    }

    @Test
    void skipsExactDuplicatePacketsAndAllowsContinuityCounterWrap() throws Exception {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] first = packet(0, true, 15, concat(new byte[]{0}, Arrays.copyOf(pat, 8)));
        byte[] second = packet(0, false, 0, Arrays.copyOfRange(pat, 8, pat.length));
        assertEquals(PROGRAM, inspect(concat(first, first.clone(), second, pmtPacket(), pesPacket()), false)
                .path("programNumber").asInt());
    }

    @Test
    void rejectsLostPsiContinuityAndNonIdenticalDuplicateCounter() {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] first = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, 8)));
        byte[] lost = packet(0, false, 2, Arrays.copyOfRange(pat, 8, pat.length));
        invalid("MEDIA_TS_PSI_CONTINUITY_INVALID", concat(first, lost, pmtPacket(), pesPacket()), false);
        byte[] conflicting = first.clone();
        conflicting[187] ^= 1;
        invalid("MEDIA_TS_PSI_CONTINUITY_INVALID", concat(first, conflicting, pmtPacket(), pesPacket()), false);
    }

    @Test
    void discontinuityDiscardsPendingPsiBeforeACompleteNewStart() throws Exception {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] pending = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, 8)));
        byte[] restarted = psiPacket(0, 7, pat);
        restarted[5] |= (byte) 0x80;
        assertEquals(PROGRAM, inspect(concat(pending, restarted, pmtPacket(), pesPacket()), false)
                .path("programNumber").asInt());
    }

    @Test
    void incompletePsiPrefixIsNotReportedAsHealthy() {
        byte[] pat = pat(PROGRAM, PMT_PID);
        byte[] pending = packet(0, true, 0, concat(new byte[]{0}, Arrays.copyOf(pat, pat.length - 1)));
        byte[] sample = concat(pending, pmtPacket(), pesPacket());
        invalid("MEDIA_TS_PREFIX_INCOMPLETE", sample, true);
        invalid("MEDIA_TS_PSI_INCOMPLETE", sample, false);
    }

    @Test
    void malformedPsiHeadersAndImpossibleSectionLengthFailBeforeCrc() {
        for (int field : new int[]{0, 1, 5}) {
            byte[] pat = pat(PROGRAM, PMT_PID);
            pat[field] = (byte) (field == 0 ? 2 : 0);
            invalid("MEDIA_TS_PSI_INVALID", transport(renewCrc(pat), standardPmt(), pesPrefix(0xe0)), false);
        }
        byte[] tooLong = packet(0, true, 0, new byte[]{0, 0, (byte) 0xbf, (byte) 0xff});
        invalid("MEDIA_TS_PSI_INVALID", concat(tooLong, pmtPacket(), pesPacket()), false);
    }

    @Test
    void pesRequiresStartIndicatorRecognizedStreamIdAndThreeByteStartCode() {
        byte[] noStart = packet(VIDEO_PID, false, 0, pesPrefix(0xe0));
        invalid("MEDIA_TS_PES_MISSING", concat(patPacket(), pmtPacket(), noStart), false);
        for (byte[] prefix : new byte[][]{{0, 0, 2, (byte) 0xe0, 0, 0}, pesPrefix(0xb0)}) {
            invalid("MEDIA_TS_PES_INVALID", transport(pat(PROGRAM, PMT_PID), standardPmt(), prefix), false);
        }
    }

    @Test
    void sixBytePesPrefixCanSpanPacketsButMissingContinuationIsIncomplete() throws Exception {
        byte[] prefix = pesPrefix(0xe0);
        byte[] first = packet(VIDEO_PID, true, 0, Arrays.copyOf(prefix, 4));
        byte[] second = packet(VIDEO_PID, false, 1, Arrays.copyOfRange(prefix, 4, 6));
        assertEquals(1, inspect(concat(patPacket(), pmtPacket(), first, second), false)
                .path("mediaStreamsWithPesPrefix").asInt());
        invalid("MEDIA_TS_PREFIX_INCOMPLETE", concat(patPacket(), pmtPacket(), first), true);
        invalid("MEDIA_TS_PES_MISSING", concat(patPacket(), pmtPacket(), first), false);
        second[3] = (byte) ((second[3] & 0xf0) | 2);
        invalid("MEDIA_TS_PES_MISSING", concat(patPacket(), pmtPacket(), first, second), false);
    }

    @Test
    void adaptationOnlyDiscontinuityCannotBeIgnoredWhileJoiningPesPrefix() {
        byte[] prefix = pesPrefix(0xe0);
        byte[] first = packet(VIDEO_PID, true, 0, Arrays.copyOf(prefix, 4));
        byte[] discontinuity = new byte[188];
        Arrays.fill(discontinuity, (byte) 0xff);
        discontinuity[0] = 0x47;
        discontinuity[1] = (byte) (VIDEO_PID >>> 8);
        discontinuity[2] = (byte) VIDEO_PID;
        discontinuity[3] = 0x2f;
        discontinuity[4] = (byte) 183;
        discontinuity[5] = (byte) 0x80;
        byte[] tail = packet(VIDEO_PID, false, 1, Arrays.copyOfRange(prefix, 4, 6));
        byte[] sample = concat(patPacket(), pmtPacket(), first, discontinuity, tail);
        invalid("MEDIA_TS_PES_MISSING", sample, false);
        invalid("MEDIA_TS_PREFIX_INCOMPLETE", sample, true);
    }

    private ObjectNode inspect(byte[] sample, boolean prefixOnly) throws IOException {
        return TsInspector.inspect(sample, prefixOnly, mapper);
    }

    private void invalid(String reason, byte[] sample, boolean prefixOnly) {
        assertEquals(reason, assertThrows(IOException.class, () -> inspect(sample, prefixOnly)).getMessage());
    }

    private void unsupported(String reason, byte[] sample) {
        assertEquals(reason, assertThrows(IllegalArgumentException.class, () -> inspect(sample, false)).getMessage());
    }

    private byte[] transport(byte[] pat, byte[] pmt, byte[] prefix) {
        return concat(psiPacket(0, 0, pat), psiPacket(PMT_PID, 0, pmt), packet(VIDEO_PID, true, 0, prefix));
    }

    private byte[] standardPmt() {
        return pmt(PROGRAM, new byte[0], elementary(0x1b, VIDEO_PID));
    }

    private byte[] patPacket() {
        return psiPacket(0, 0, pat(PROGRAM, PMT_PID));
    }

    private byte[] pmtPacket() {
        return psiPacket(PMT_PID, 0, standardPmt());
    }

    private byte[] pesPacket() {
        return packet(VIDEO_PID, true, 0, pesPrefix(0xe0));
    }
}
