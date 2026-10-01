package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-built tar archives exercising entries that TarBuilder cannot express.
 */
public final class MaliciousTars {

    private static final int BLOCK = 512;

    private MaliciousTars() {
    }

    public static byte[] deviceNode(String name) {
        byte[] header = baseHeader(name, 0, (byte) '3');
        putOctal(header, 329, 8, 1);
        putOctal(header, 337, 8, 3);
        checksum(header);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    public static byte[] named(String name, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = baseHeader(name, data.length, (byte) '0');
        checksum(header);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(data);
        out.writeBytes(new byte[(BLOCK - (data.length % BLOCK)) % BLOCK]);
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    public static byte[] paxHeader() {
        byte[] body = "16 path=evil-long\n".getBytes(StandardCharsets.UTF_8);
        byte[] pax = baseHeader("PaxHeader/meta", body.length, (byte) 'x');
        checksum(pax);

        byte[] regular = baseHeader("safe.txt", 0, (byte) '0');
        checksum(regular);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pax);
        out.writeBytes(body);
        out.writeBytes(new byte[(BLOCK - (body.length % BLOCK)) % BLOCK]);
        out.writeBytes(regular);
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    public static byte[] gnuLongLink() {
        byte[] body = "very/long/path/that/exceeds/one/hundred/characters/limit/abcdefghijklmnop"
                .getBytes(StandardCharsets.UTF_8);
        byte[] gnu = baseHeader("././@LongLink", body.length, (byte) 'L');
        checksum(gnu);

        byte[] regular = baseHeader("placeholder.txt", 0, (byte) '0');
        checksum(regular);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(gnu);
        out.writeBytes(body);
        out.writeBytes(new byte[(BLOCK - (body.length % BLOCK)) % BLOCK]);
        out.writeBytes(regular);
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    public static byte[] badMagic() {
        byte[] header = baseHeader("a.txt", 0, (byte) '0');
        for (int i = 257; i < 263; i++) {
            header[i] = 0;
        }
        checksum(header);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    private static byte[] baseHeader(String name, long size, byte typeFlag) {
        byte[] header = new byte[BLOCK];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, 0644);
        putOctal(header, 124, 12, size);
        header[156] = typeFlag;
        putString(header, 257, 6, "ustar ");
        header[263] = ' ';
        return header;
    }

    private static void checksum(byte[] header) {
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        putOctal(header, 148, 7, sum);
        header[155] = ' ';
    }

    private static void putString(byte[] buffer, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buffer, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] buffer, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        String padded = octal.length() >= length
                ? octal.substring(octal.length() - length)
                : "0".repeat(length - octal.length()) + octal;
        putString(buffer, offset, length, padded);
    }
}
