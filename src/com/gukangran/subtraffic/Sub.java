package com.gukangran.subtraffic;

/** One saved subscription plus the last query result. Pure Java model. */
public final class Sub {

    public String url = "";
    public String name = "";
    /** name typed by the user; when set it wins over the profile title and survives refreshes */
    public String customName = "";

    public boolean hasInfo = false;
    public long upload = -1L;
    public long download = -1L;
    public long total = -1L;
    public long expire = -1L;

    public String rawHeader = "";
    public long lastQuery = 0L;
    public String error = "";
    /** Key of the method that worked last time (see Strategies.Attempt.key()). */
    public String method = "";
    /** Text shown while a query is running. */
    public transient boolean loading = false;
    /** Per-attempt diagnostic log of the last query (not persisted). */
    public transient String diag = "";
    /** Short text shown in the card's top-right corner while refreshing (not persisted). */
    public transient String progressNote = "";

    public Userinfo info() {
        Userinfo u = new Userinfo();
        u.upload = upload < 0L ? 0L : upload;
        u.download = download < 0L ? 0L : download;
        u.total = total < 0L ? 0L : total;
        u.expire = expire;
        u.raw = rawHeader;
        return u;
    }

    public void apply(Userinfo u, String profileTitle) {
        hasInfo = u != null;
        if (u != null) {
            upload = u.upload;
            download = u.download;
            total = u.total;
            expire = u.expire;
            rawHeader = u.raw;
        } else {
            upload = download = total = -1L;
            expire = -1L;
            rawHeader = "";
        }
        if (profileTitle != null && !profileTitle.isEmpty()) {
            name = profileTitle;
        } else if (name == null || name.isEmpty()) {
            name = hostOf(url);
        }
        lastQuery = System.currentTimeMillis();
        error = "";
    }

    public void fail(String message) {
        error = message == null ? "未知错误" : message;
        lastQuery = System.currentTimeMillis();
    }

    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return "(未命名)";
        }
        try {
            String s = url;
            int scheme = s.indexOf("://");
            if (scheme >= 0) {
                s = s.substring(scheme + 3);
            }
            int slash = s.indexOf('/');
            if (slash >= 0) {
                s = s.substring(0, slash);
            }
            return s.isEmpty() ? url : s;
        } catch (Exception e) {
            return url;
        }
    }

    public String displayName() {
        if (customName != null && !customName.isEmpty()) {
            return customName;
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return hostOf(url);
    }
}