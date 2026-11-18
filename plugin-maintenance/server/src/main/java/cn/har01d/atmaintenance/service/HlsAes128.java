package cn.har01d.atmaintenance.service;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** CBC prefix inspection is unauthenticated and never proves complete media playback. */
final class HlsAes128 {
    static byte[] iv(String explicit, BigInteger sequence) {
        byte[] number = (explicit == null ? sequence : new BigInteger(explicit.substring(2), 16)).toByteArray();
        byte[] result = new byte[16];
        int size = Math.min(number.length, result.length);
        System.arraycopy(number, number.length - size, result, result.length - size, size);
        return result;
    }

    static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] iv, boolean prefixOnly) throws IOException {
        int size = prefixOnly ? ciphertext.length / 16 * 16 : ciphertext.length;
        if (prefixOnly && size == 0) throw new IOException("MEDIA_HLS_CIPHER_PREFIX_INCOMPLETE");
        if (key.length != 16 || iv.length != 16 || size == 0 || size % 16 != 0)
            throw new IOException("MEDIA_HLS_CIPHER_INVALID");
        try {
            Cipher cipher = Cipher.getInstance(prefixOnly ? "AES/CBC/NoPadding" : "AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            // An unfinished CBC suffix cannot be padded or interpreted as a complete segment.
            return cipher.doFinal(ciphertext, 0, size);
        } catch (GeneralSecurityException error) {
            throw new IOException("MEDIA_HLS_DECRYPT_FAILED");
        }
    }
}
