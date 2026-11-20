package cn.har01d.atmaintenance.service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HlsAes128Test {
    static byte[] hex(String value) { return HexFormat.of().parseHex(value); }

    @Test
    void nistCbcPrefixVectorMatchesWithoutRoundTripAssumptions() throws Exception {
        byte[] key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = hex("000102030405060708090a0b0c0d0e0f");
        byte[] cipher = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2");
        assertArrayEquals(hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"),
                HlsAes128.decrypt(cipher, key, iv, true));
        assertArrayEquals(hex("6bc1bee22e409f96e93d7e117393172a"),
                HlsAes128.decrypt(Arrays.copyOf(cipher, 31), key, iv, true));
    }

    @Test
    void implicitAndExplicitIvAre128BitBigEndian() {
        assertArrayEquals(hex("00000000000000000000000000000007"), HlsAes128.iv(null, BigInteger.valueOf(7)));
        assertArrayEquals(hex("0000000000000000ffffffffffffffff"),
                HlsAes128.iv(null, new BigInteger("18446744073709551615")));
        assertArrayEquals(hex("ffffffffffffffffffffffffffffffff"),
                HlsAes128.iv("0xffffffffffffffffffffffffffffffff", BigInteger.ZERO));
        assertArrayEquals(hex("00000000000000000000000000000001"), HlsAes128.iv("0X1", BigInteger.ZERO));
    }

    @Test
    void completeSegmentsCheckPaddingAndRejectMalformedCiphertext() throws Exception {
        byte[] key = new byte[16], iv = new byte[16], plain = TsFixtures.stream();
        byte[] encrypted = encrypt(plain, key, iv);
        assertArrayEquals(plain, HlsAes128.decrypt(encrypted, key, iv, false));
        // Last ciphertext block is altered via its previous block, deterministically corrupting padding.
        encrypted[encrypted.length - 17] ^= 1;
        assertEquals("MEDIA_HLS_DECRYPT_FAILED", assertThrows(IOException.class,
                () -> HlsAes128.decrypt(encrypted, key, iv, false)).getMessage());
        for (int size : List.of(0, 1, 15, 17, 31)) {
            assertEquals("MEDIA_HLS_CIPHER_INVALID", assertThrows(IOException.class,
                    () -> HlsAes128.decrypt(new byte[size], key, iv, false)).getMessage());
        }
        assertEquals("MEDIA_HLS_CIPHER_PREFIX_INCOMPLETE", assertThrows(IOException.class,
                () -> HlsAes128.decrypt(new byte[15], key, iv, true)).getMessage());
    }

    static byte[] encrypt(byte[] body, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(body);
    }
}
