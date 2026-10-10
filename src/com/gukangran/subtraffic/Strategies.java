package com.gukangran.subtraffic;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ordered list of ways to ask a panel for quota information.
 *
 * Why more than one attempt: airports differ. Some only emit the
 * "subscription-userinfo" header when the request looks like a client they
 * recognise (Hiddify / Clash / sing-box ...), and some panels are unreachable
 * unless the traffic goes through the local proxy of whatever VPN app is
 * already running. So we try the configured User-Agent first, then a set of
 * common client UAs, then the usual local proxy ports.
 *
 * Pure Java on purpose: no Android dependency, so the ordering can be tested
 * on a desktop JDK.
 */
public final class Strategies {

    public static final class Attempt {
        public final String label;
        /** null = use the User-Agent configured in settings */
        public final String ua;
        public final boolean proxy;
        public final Proxy.Type proxyType;
        public final int port;

        Attempt(String label, String ua, boolean proxy, Proxy.Type proxyType, int port) {
            this.label = label;
            this.ua = ua;
            this.proxy = proxy;
            this.proxyType = proxyType;
            this.port = port;
        }

        /** Stable identifier, persisted so the next refresh can reuse this exact method. */
        public String key() {
            if (!proxy) {
                return (ua == null) ? "direct" : ("direct|" + ua);
            }
            return (proxyType == Proxy.Type.HTTP ? "http|" : "socks|") + port;
        }
    }

    /** Common clients; panels that check the UA usually accept one of these. */
    public static final String[] FALLBACK_UAS = {
            "v2rayNG/2.3.4",
            "Hiddify/2.5.7",
            "ClashforWindows/0.20.39",
            "ClashMetaForAndroid/2.11.1",
            "sing-box/1.10.0",
            "v2rayN/6.60",
            "Shadowrocket/2.2.20",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    };

    /** Usual SOCKS ports: v2rayNG 10808, Hiddify 2334, Clash 7890. */
    public static final int[] SOCKS_PORTS = {10808, 2334, 7890};
    /** v2rayNG's HTTP proxy port. */
    public static final int HTTP_PORT = 10809;

    private Strategies() {
    }

    public static final int MODE_AUTO = 0;
    public static final int MODE_DIRECT = 1;
    public static final int MODE_PROXY = 2;

    public static List<Attempt> build(String configuredUa, int mode) {
        List<Attempt> out = new ArrayList<Attempt>();
        if (mode != MODE_PROXY) {
            out.add(new Attempt("直连（默认 UA）", null, false, null, 0));
            for (int i = 0; i < FALLBACK_UAS.length; i++) {
                String ua = FALLBACK_UAS[i];
                if (configuredUa != null && configuredUa.equals(ua)) {
                    continue;
                }
                out.add(new Attempt("直连 · UA=" + shortUa(ua), ua, false, null, 0));
            }
        }
        if (mode != MODE_DIRECT) {
            for (int i = 0; i < SOCKS_PORTS.length; i++) {
                int p = SOCKS_PORTS[i];
                out.add(new Attempt("本机 SOCKS 代理 " + p, null, true, Proxy.Type.SOCKS, p));
            }
            out.add(new Attempt("本机 HTTP 代理 " + HTTP_PORT, null, true, Proxy.Type.HTTP, HTTP_PORT));
        }
        return out;
    }

    /** Rebuilds a single attempt from a stored key; null when the key is unknown. */
    public static Attempt byKey(String key, String configuredUa) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        List<Attempt> all = build(configuredUa, MODE_AUTO);
        for (int i = 0; i < all.size(); i++) {
            if (key.equals(all.get(i).key())) {
                return all.get(i);
            }
        }
        if ("direct".equals(key)) {
            return new Attempt("直连（默认 UA）", null, false, null, 0);
        }
        if (key.startsWith("direct|")) {
            String ua = key.substring(7);
            return new Attempt("直连 · UA=" + shortUa(ua), ua, false, null, 0);
        }
        if (key.startsWith("socks|")) {
            int p = toPort(key.substring(6));
            if (p > 0) {
                return new Attempt("本机 SOCKS 代理 " + p, null, true, Proxy.Type.SOCKS, p);
            }
        }
        if (key.startsWith("http|")) {
            int p = toPort(key.substring(5));
            if (p > 0) {
                return new Attempt("本机 HTTP 代理 " + p, null, true, Proxy.Type.HTTP, p);
            }
        }
        return null;
    }

    private static int toPort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * When a direct attempt fails without even getting an HTTP response (DNS failure,
     * timeout, TLS error), trying the remaining User-Agents is pointless - the request
     * never reached the panel. Skip the rest of the direct attempts and go straight to
     * the local proxies instead of waiting through eight identical failures.
     */
    public static boolean skipDirectAfterNetworkFailure(boolean networkDown, Attempt a) {
        return networkDown && !a.proxy;
    }

    public static String shortUa(String ua) {
        if (ua == null) {
            return "默认";
        }
        if (ua.startsWith("Mozilla")) {
            return "浏览器";
        }
        int slash = ua.indexOf('/');
        String s = (slash > 0) ? ua.substring(0, Math.min(ua.length(), slash + 5)) : ua;
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}