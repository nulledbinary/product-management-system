package com.hopepms.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Audit stamp formatter. Matches the original spec format:
 *   ACTION USERID YYYY-MM-DD HH:MM
 */
public final class StampHelper {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private StampHelper() {}

    public static String make(String action, String userId) {
        return action + " " + userId + " " + LocalDateTime.now().format(FMT);
    }
}
