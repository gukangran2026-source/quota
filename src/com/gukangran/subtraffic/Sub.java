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

    /** 上次刷新时的失败原因分类，用来在卡片上说人话（不持久化）。 */
    public transient int failKind = FAIL_NONE;
    /** 拿到过流量信息，但这次没拿到（卡片上仍显示旧数据，只是标一句）。 */
    public static final int FAIL_NONE = 0;
    /** 服务器限流（HTTP 429）：不是链接坏了，等一会儿再刷。 */
    public static final int FAIL_RATE_LIMIT = 1;
    /** 服务器说订阅链接失效/过期（HTTP 404 等）：要去面板重新复制链接。 */
    public static final int FAIL_EXPIRED = 2;
    /** 服务器判定令牌无效（token is error）：同链接换什么都白搭。 */
    public static final int FAIL_TOKEN = 3;
    /** 一条路都没走通：可能是没网 / 没开 VPN / 代理端口不对。 */
    public static final int FAIL_NO_ROUTE = 4;

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
        failKind = FAIL_NONE;
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

    /** 失败但保留上次的流量数据（限流、网络问题不该把已有数字清掉）。 */
    public void failKeepingData(String message, int kind) {
        error = message == null ? "未知错误" : message;
        failKind = kind;
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