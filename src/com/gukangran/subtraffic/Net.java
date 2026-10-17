package com.gukangran.subtraffic;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches a subscription URL and keeps BOTH the response headers (that is
 * where the quota lives) and a short body preview (useful when a panel does
 * not send the quota header - the preview usually reveals why: an HTML login
 * page, an error message, a Clash YAML ...).
 *
 * Only HttpURLConnection is used, so there is no third-party dependency.
 * Redirects are followed by hand because HttpURLConnection refuses to follow
 * a redirect that switches protocol.
 */
public final class Net {

    public static final String DEFAULT_UA = "v2rayNG/2.3.4";
    private static final int MAX_REDIRECTS = 8;
    private static final int PREVIEW_LIMIT = 512;

    public static final class Resp {
        public int code = 0;
        public String error = null;
        public String finalUrl = "";
        public String bodyPreview = "";
        public final Map<String, String> headers = new HashMap<String, String>();

        public String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.US));
            return v == null ? "" : v;
        }
    }

    private Net() {
    }

    public static Resp get(String urlStr, String userAgent, boolean viaProxy,
                           Proxy.Type proxyType, int proxyPort) {
        return get(urlStr, userAgent, viaProxy, "127.0.0.1", proxyType, proxyPort, 7000, 9000);
    }

    public static Resp get(String urlStr, String userAgent, boolean viaProxy, String proxyHost,
                           Proxy.Type proxyType, int proxyPort, int connectTimeout, int readTimeout) {
        Resp r = new Resp();
        String current = urlStr;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                URL url = new URL(current);
                HttpURLConnection conn;
                if (viaProxy) {
                    Proxy proxy = new Proxy(proxyType, new InetSocketAddress(proxyHost, proxyPort));
                    conn = (HttpURLConnection) url.openConnection(proxy);
                } else {
                    conn = (HttpURLConnection) url.openConnection();
                }
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(readTimeout);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent",
                        (userAgent == null || userAgent.isEmpty()) ? DEFAULT_UA : userAgent);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                conn.setRequestProperty("Connection", "close");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        r.code = code;
                        r.error = "重定向缺少 Location 头";
                        return r;
                    }
                    current = new URL(url, loc).toString();
                    continue;
                }

                r.code = code;
                r.finalUrl = current;
                for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                        continue;
                    }
                    String key = e.getKey().toLowerCase(Locale.US);
                    if (!r.headers.containsKey(key)) {
                        r.headers.put(key, e.getValue().get(0));
                    }
                }
                r.bodyPreview = readPreview(conn, code);
                conn.disconnect();
                if (code >= 400) {
                    r.error = "HTTP " + code;
                }
                return r;
            }
            r.error = "重定向次数过多（>" + MAX_REDIRECTS + "）";
        } catch (Exception e) {
            String msg = e.getMessage();
            r.error = e.getClass().getSimpleName() + (msg == null ? "" : (": " + msg));
        }
        return r;
    }

    private static String readPreview(HttpURLConnection conn, int code) {
        InputStream in = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                byte[] buf = new byte[256];
                int read;
                int total = 0;
                while ((read = in.read(buf)) > 0 && total < PREVIEW_LIMIT) {
                    bos.write(buf, 0, read);
                    total += read;
                }
            }
        } catch (Exception ignored) {
            // headers are already captured; the body is only for diagnostics
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return sanitize(bos.toByteArray());
    }

    /**
     * True when the server clearly rejected the subscription token itself, e.g.
     * {"message":"token is error"}. In that case retrying other User-Agents or other
     * transports is pointless - no client can read the quota from that link.
     */
    public static boolean isTokenRejected(Resp r) {
        if (r == null) {
            return false;
        }
        String body = r.bodyPreview == null ? "" : r.bodyPreview.toLowerCase(Locale.US);
        if (body.isEmpty()) {
            return false;
        }
        // 只有“服务器明确说这条链接不能用”才算令牌被拒。普通的 404/403 页面、
        // 限流页、HTML 错误页都不算——那两种情况另有专门提示。
        if (body.contains("token is error") || body.contains("token is invalid")
                || body.contains("invalid token") || body.contains("token expired")
                || body.contains("令牌无效") || body.contains("令牌已失效")) {
            return true;
        }
        boolean tokenWord = body.contains("token") || body.contains("令牌") || body.contains("key");
        boolean badWord = body.contains("invalid") || body.contains("expired")
                || body.contains("无效") || body.contains("过期") || body.contains("失效");
        // 只有很短的纯文本错误（不是一整页 HTML）才信这套关键词，
        // 否则 <html>…error…</html> 这种页面会被误判成令牌失效。
        return tokenWord && badWord && body.length() < 300 && !body.contains("<html")
                && !body.contains("<!doctype");
    }

    /** 服务器限流（请求太频繁）。换个 UA 再打一次只会更严重，应该停下来等。 */
    public static boolean isRateLimited(Resp r) {
        return r != null && r.code == 429;
    }

    /**
     * 服务器明确说订阅链接本身失效/过期（面板常见：404 + "Subscription link is
     * invalid or expired"）。这种情况换 UA 也没用，但和令牌被拒不同——需要去面板
     * 重新复制一条新链接。
     */
    public static boolean isLinkExpired(Resp r) {
        if (r == null || r.code == 0 || r.code == 429) {
            return false;
        }
        String body = r.bodyPreview == null ? "" : r.bodyPreview.toLowerCase(Locale.US);
        if (body.isEmpty()) {
            return false;
        }
        if (body.contains("subscription link is invalid")
                || body.contains("link is invalid or expired")
                || body.contains("链接已失效") || body.contains("订阅已过期")
                || body.contains("订阅链接无效")) {
            return true;
        }
        return r.code == 404 && body.length() < 300 && !body.contains("<html");
    }

    private static String sanitize(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        String s;
        try {
            s = new String(data, "UTF-8");
        } catch (Exception e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= 0x20) {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.length() > 180 ? out.substring(0, 180) + "…" : out;
    }
}