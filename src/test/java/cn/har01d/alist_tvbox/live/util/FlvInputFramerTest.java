package cn.har01d.alist_tvbox.live.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** FLV 分帧器:任意切块边界下按 DataSize 定界出完整包,畸形流拒收。 */
class FlvInputFramerTest {

    @Test
    void framesHeaderAndTagsAcrossByteByByteFeeding() throws Exception {
        byte[] header = {0x46, 0x4c, 0x56, 1, 5, 0, 0, 0, 9, 0, 0, 0, 0};
        byte[] video = FlvSpliceSessionTest.tag(9, 1234, new byte[]{0x17, 0, 0, 0, 0, 1, 2});
        byte[] audio = FlvSpliceSessionTest.tag(8, 1235, new byte[]{(byte) 0xaf, 1, 9});
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(header);
        stream.writeBytes(video);
        stream.writeBytes(audio);

        FlvInputFramer framer = new FlvInputFramer();
        for (byte b : stream.toByteArray()) {
            framer.add(new byte[]{b}, 0, 1);
        }
        List<byte[]> packets = new ArrayList<>();
        while (framer.hasNext()) {
            packets.add(framer.next());
        }
        assertEquals(3, packets.size());
        assertArrayEquals(header, packets.get(0));
        assertArrayEquals(video, packets.get(1));
        assertArrayEquals(audio, packets.get(2));
    }

    @Test
    void honorsNonDefaultHeaderSize() throws Exception {
        // headerSize=12:9 字节签名头 + 3 字节扩展,再接 4 字节 PreviousTagSize0
        byte[] header = {0x46, 0x4c, 0x56, 1, 5, 0, 0, 0, 12, 1, 2, 3, 0, 0, 0, 0};
        FlvInputFramer framer = new FlvInputFramer();
        framer.add(header, 0, header.length);
        assertEquals(1, count(framer));
        framer.next();
        assertEquals(0, count(framer));
    }

    @Test
    void rejectsNonFlvSignature() {
        FlvInputFramer framer = new FlvInputFramer();
        assertThrows(IOException.class, () -> framer.add(new byte[]{'M', 'P', '4', 1, 5, 0, 0, 0, 9}, 0, 9));
    }

    @Test
    void rejectsInvalidInitialTagSize() {
        FlvInputFramer framer = new FlvInputFramer();
        byte[] header = {0x46, 0x4c, 0x56, 1, 5, 0, 0, 0, 9, 0, 0, 0, 7};
        assertThrows(IOException.class, () -> framer.add(header, 0, header.length));
    }

    private static int count(FlvInputFramer framer) {
        int count = 0;
        while (framer.hasNext()) {
            framer.next();
            count++;
        }
        return count;
    }
}
