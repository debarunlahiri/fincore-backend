package com.fincore.common.util;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Customer-facing reference numbers such as {@code TXN2611278F3K2Q9A}: prefix, date, 8 random
 * characters. They are the only identifiers customers ever see. Internal database ids never leave
 * the service, so they cannot be probed or enumerated. The alphabet skips look-alike characters (I,
 * O, 0, 1) so they can be read out.
 */
public final class Reference {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private Reference() {}

    public static String next(String prefix) {
        StringBuilder sb =
                new StringBuilder(prefix).append(LocalDate.now(ZoneOffset.UTC).format(DAY));
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
