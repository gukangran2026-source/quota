package com.gukangran.subtraffic;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialises the subscription list into a single string so it can be kept in
 * SharedPreferences without pulling in a JSON library.
 *
 * Record layout: field1 \u0001 field2 ... ; records separated by \n.
 * Every field is URL-encoded, so delimiters can never appear inside a value.
 */
public final class SubCodec {

    private static final char FIELD_SEP = '\u0001';
    /** v1.0 records had 8 fields; v1.1 added the remembered method (9); v2.1 added customName (10). */
    private static final int MIN_FIELDS = 8;
    private static final int FIELD_COUNT = 10;

    private SubCodec() {
    }

    public static String encode(List<Sub> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Sub s = list.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(enc(s.url)).append(FIELD_SEP)
              .append(enc(s.name)).append(FIELD_SEP)
              .append(s.hasInfo ? "1" : "0").append(FIELD_SEP)
              .append(s.upload).append(FIELD_SEP)
              .append(s.download).append(FIELD_SEP)
              .append(s.total).append(FIELD_SEP)
              .append(s.expire).append(FIELD_SEP)
              .append(s.lastQuery).append(FIELD_SEP)
              .append(enc(s.method)).append(FIELD_SEP)
              .append(enc(s.customName));
        }
        return sb.toString();
    }

    public static List<Sub> decode(String data) {
        List<Sub> out = new ArrayList<Sub>();
        if (data == null || data.isEmpty()) {
            return out;
        }
        String[] records = data.split("\n");
        for (int i = 0; i < records.length; i++) {
            String rec = records[i];
            if (rec.isEmpty()) {
                continue;
            }
            String[] f = rec.split(String.valueOf(FIELD_SEP), -1);
            if (f.length < MIN_FIELDS) {
                continue;
            }
            Sub s = new Sub();
            s.url = dec(f[0]);
            s.name = dec(f[1]);
            s.hasInfo = "1".equals(f[2]);
            s.upload = num(f[3], -1L);
            s.download = num(f[4], -1L);
            s.total = num(f[5], -1L);
            s.expire = num(f[6], -1L);
            s.lastQuery = num(f[7], 0L);
            if (f.length >= FIELD_COUNT) {
                s.method = dec(f[8]);
                s.customName = dec(f[9]);
            } else if (f.length >= 9) {
                s.method = dec(f[8]);
            }
            out.add(s);
        }
        return out;
    }

    private static long num(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String enc(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}