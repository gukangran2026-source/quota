package com.gukangran.subtraffic;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Parses the "subscription-userinfo" HTTP response header used by most
 * subscription panels (Marzban / X-UI / V2board / Xboard ...) and formats
 * the values for display.
 *
 * Header format: upload=123; download=456; total=789; expire=1700000000
 * Units: bytes. expire is a unix timestamp in seconds.
 * Convention (same as other clients): total=0 means unlimited, expire=0 means never expires.
 *
 * Pure Java on purpose: no Android dependency, so it can be unit tested on a desktop JDK.
 */
public final class Userinfo {

    public long upload = -1L;
    public long download = -1L;
    public long total = -1L;
    public long expire = -1L;
    public String raw = "";

    public static Userinfo parse(String value) {
        if (value == null) {
            return null;
        }
        Userinfo u = new Userinfo();
        u.raw = value.trim();
        boolean any = false;
        String[] parts = u.raw.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.US);
            String val = part.substring(eq + 1).trim();
            long num;
            try {
                num = Long.parseLong(val);
            } catch (NumberFormatException e) {
                continue;
            }
            if ("upload".equals(key)) {
                u.upload = num;
                any = true;
            } else if ("download".equals(key)) {
                u.download = num;
                any = true;
            } else if ("total".equals(key)) {
                u.total = num;
                any = true;
            } else if ("expire".equals(key)) {
                u.expire = num;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        if (u.upload < 0) {
            u.upload = 0L;
        }
        if (u.download < 0) {
            u.download = 0L;
        }
        if (u.total < 0) {
            u.total = 0L;
        }
        return u;
    }

    public long used() {
        return upload + download;
    }

    public boolean unlimited() {
        return total <= 0L;
    }

    /** Remaining bytes, or -1 when the quota is unlimited. */
    public long remaining() {
        if (unlimited()) {
            return -1L;
        }
        long r = total - used();
        return r < 0L ? 0L : r;
    }

    /** Used quota in percent, or -1 when unlimited. */
    public int usedPercent() {
        if (unlimited()) {
            return -1;
        }
        long p = used() * 100L / total;
        if (p < 0L) {
            p = 0L;
        }
        return (int) (p > 100L ? 100L : p);
    }

    public boolean noExpire() {
        return expire <= 0L;
    }

    // ---------------------------------------------------------------- formatting

    public static String fmtBytes(long bytes) {
        if (bytes < 0L) {
            return "—";
        }
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int i = 0;
        while (v >= 1024d && i < units.length - 1) {
            v /= 1024d;
            i++;
        }
        if (i <= 1) {
            return String.format(Locale.US, "%.0f %s", v, units[i]);
        }
        return String.format(Locale.US, "%.2f %s", v, units[i]);
    }

    public static String fmtDate(long epochSeconds) {
        if (epochSeconds <= 0L) {
            return "不过期";
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(epochSeconds * 1000L));
    }

    public static String fmtRemainTime(long epochSeconds) {
        if (epochSeconds <= 0L) {
            return "";
        }
        long ms = epochSeconds * 1000L - System.currentTimeMillis();
        if (ms < 0L) {
            return "已过期";
        }
        long days = ms / 86400000L;
        long hours = (ms % 86400000L) / 3600000L;
        if (days > 0L) {
            return "剩 " + days + " 天";
        }
        if (hours > 0L) {
            return "剩 " + hours + " 小时";
        }
        return "不足 1 小时";
    }

    /** One-line summary of the remaining quota. */
    public String summaryRemaining() {
        if (unlimited()) {
            return "剩余：不限量";
        }
        return "剩余：" + fmtBytes(remaining()) + " / 共 " + fmtBytes(total);
    }

    /** One-line summary of the used quota. */
    public String summaryUsed() {
        StringBuilder sb = new StringBuilder();
        sb.append("已用 ").append(fmtBytes(used()));
        if (!unlimited()) {
            sb.append("（").append(usedPercent()).append("%）");
        }
        sb.append("　↑").append(fmtBytes(upload)).append(" ↓").append(fmtBytes(download));
        return sb.toString();
    }

    /** One-line summary of the expire time. */
    public String summaryExpire() {
        if (noExpire()) {
            return "到期：不过期";
        }
        String rest = fmtRemainTime(expire);
        return "到期：" + fmtDate(expire) + (rest.isEmpty() ? "" : "（" + rest + "）");
    }
}