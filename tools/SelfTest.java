import com.gukangran.subtraffic.Net;
import com.gukangran.subtraffic.Strategies;
import com.gukangran.subtraffic.Sub;
import com.gukangran.subtraffic.SubCodec;
import com.gukangran.subtraffic.Userinfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop self test for the pure-Java parts (no Android APIs).
 * Usage: java SelfTest [url-to-query]
 */
public class SelfTest {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testParseNormal();
        testParseUnlimited();
        testParseNoExpire();
        testParseBroken();
        testFormatting();
        testStrategies();
        testStrategyKeys();
        testSkipPolicy();
        testTokenRejected();
        testCodecRoundTrip();
        testCodecBackwardCompat();
        if (args.length > 0) {
            testLiveQuery(args[0]);
        } else {
            System.out.println("[skip] live query (no url given)");
        }
        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void testParseNormal() {
        Userinfo u = Userinfo.parse("upload=1073741824; download=2147483648; total=107374182400; expire=1800000000");
        check("parse.upload", u != null && u.upload == 1073741824L);
        check("parse.download", u != null && u.download == 2147483648L);
        check("parse.total", u != null && u.total == 107374182400L);
        check("parse.used", u != null && u.used() == 3221225472L);
        check("parse.remaining", u != null && u.remaining() == 104152956928L);
        check("parse.percent", u != null && u.usedPercent() == 3);
    }

    private static void testParseUnlimited() {
        Userinfo u = Userinfo.parse("upload=10; download=20; total=0; expire=1800000000");
        check("unlimited.flag", u != null && u.unlimited());
        check("unlimited.remaining", u != null && u.remaining() == -1L);
        check("unlimited.percent", u != null && u.usedPercent() == -1);
        check("unlimited.text", u != null && u.summaryRemaining().equals("剩余：不限量"));
    }

    private static void testParseNoExpire() {
        Userinfo u = Userinfo.parse("upload=1; download=2; total=1000; expire=0");
        check("noexpire.flag", u != null && u.noExpire());
        check("noexpire.text", u != null && u.summaryExpire().contains("不过期"));
    }

    private static void testParseBroken() {
        check("broken.null", Userinfo.parse(null) == null);
        check("broken.empty", Userinfo.parse("") == null);
        check("broken.garbage", Userinfo.parse("hello world") == null);
        check("broken.spaces", Userinfo.parse(" upload = 5 ; download = 5 ; total = 100 ; expire = 0 ") != null);
        check("broken.partial", Userinfo.parse("total=100") != null);
        Userinfo u = Userinfo.parse("total=100");
        check("broken.partial.upload", u != null && u.upload == 0L);
    }

    private static void testFormatting() {
        check("fmt.zero", Userinfo.fmtBytes(0L).equals("0 B"));
        check("fmt.gb", Userinfo.fmtBytes(1073741824L).equals("1.00 GB"));
        check("fmt.tb", Userinfo.fmtBytes(1099511627776L).equals("1.00 TB"));
        check("fmt.neg", Userinfo.fmtBytes(-1L).equals("—"));
        check("fmt.date", Userinfo.fmtDate(0L).equals("不过期"));
        check("fmt.past", Userinfo.fmtRemainTime(1000000000L).equals("已过期"));
    }

    private static void testCodecRoundTrip() {
        List<Sub> in = new ArrayList<Sub>();
        Sub a = new Sub();
        a.url = "https://example.com/sub?token=abc=1&x=2#备注";
        a.name = "我的订阅 | 测试";
        a.hasInfo = true;
        a.upload = 123L;
        a.download = 456L;
        a.total = 789L;
        a.expire = 1700000000L;
        a.lastQuery = 1712345678900L;
        a.method = "socks|10808";
        in.add(a);
        Sub b = new Sub();
        b.url = "https://b.example.com/s";
        in.add(b);

        String encoded = SubCodec.encode(in);
        List<Sub> out = SubCodec.decode(encoded);
        check("codec.size", out.size() == 2);
        check("codec.url", out.get(0).url.equals(a.url));
        check("codec.name", out.get(0).name.equals(a.name));
        check("codec.hasInfo", out.get(0).hasInfo);
        check("codec.nums", out.get(0).upload == 123L && out.get(0).download == 456L
                && out.get(0).total == 789L && out.get(0).expire == 1700000000L);
        check("codec.lastQuery", out.get(0).lastQuery == 1712345678900L);
        check("codec.method", out.get(0).method.equals("socks|10808"));
        check("codec.second", out.get(1).url.equals(b.url) && !out.get(1).hasInfo);
        check("codec.empty", SubCodec.decode("").size() == 0);
        testModelApply();
    }

    private static void testStrategies() {
        List<Strategies.Attempt> auto = Strategies.build("v2rayNG/2.3.4", Strategies.MODE_AUTO);
        check("strat.auto.first", auto.get(0).label.startsWith("直连") && auto.get(0).ua == null);
        check("strat.auto.noDupUa", auto.size() == 1 + (Strategies.FALLBACK_UAS.length - 1)
                + Strategies.SOCKS_PORTS.length + 1);
        check("strat.auto.proxyLast", auto.get(auto.size() - 1).proxy);
        boolean directOnly = true;
        List<Strategies.Attempt> direct = Strategies.build("v2rayNG/2.3.4", Strategies.MODE_DIRECT);
        for (int i = 0; i < direct.size(); i++) {
            if (direct.get(i).proxy) {
                directOnly = false;
            }
        }
        check("strat.direct.noProxy", directOnly);
        List<Strategies.Attempt> viaProxy = Strategies.build("v2rayNG/2.3.4", Strategies.MODE_PROXY);
        boolean allProxy = !viaProxy.isEmpty();
        for (int i = 0; i < viaProxy.size(); i++) {
            if (!viaProxy.get(i).proxy) {
                allProxy = false;
            }
        }
        check("strat.proxy.onlyProxy", allProxy);
        check("strat.shortUa.browser", Strategies.shortUa(Strategies.FALLBACK_UAS[7]).equals("浏览器"));
        check("strat.shortUa.null", Strategies.shortUa(null).equals("默认"));
    }

    private static void testStrategyKeys() {
        List<Strategies.Attempt> all = Strategies.build("v2rayNG/2.3.4", Strategies.MODE_AUTO);
        check("key.direct", all.get(0).key().equals("direct"));
        check("key.ua", all.get(1).key().startsWith("direct|"));
        check("key.socks", all.get(all.size() - 2).key().startsWith("socks|"));
        check("key.http", all.get(all.size() - 1).key().equals("http|10809"));

        Strategies.Attempt ua = Strategies.byKey("direct|Hiddify/2.5.7", "v2rayNG/2.3.4");
        check("key.byKey.ua", ua != null && "Hiddify/2.5.7".equals(ua.ua) && !ua.proxy);
        Strategies.Attempt socks = Strategies.byKey("socks|10808", "v2rayNG/2.3.4");
        check("key.byKey.socks", socks != null && socks.proxy && socks.port == 10808);
        Strategies.Attempt http = Strategies.byKey("http|10809", "v2rayNG/2.3.4");
        check("key.byKey.http", http != null && http.proxy && http.port == 10809);
        check("key.byKey.unknown", Strategies.byKey("weird|x", "v2rayNG/2.3.4") == null);
        check("key.byKey.empty", Strategies.byKey("", "v2rayNG/2.3.4") == null);
    }

    private static void testTokenRejected() {
        Net.Resp ok = new Net.Resp();
        ok.code = 200;
        ok.bodyPreview = "#profile-title: x";
        check("token.ok.notRejected", !Net.isTokenRejected(ok));

        Net.Resp rejected = new Net.Resp();
        rejected.code = 403;
        rejected.bodyPreview = "{ \"message\": \"token is error\" }";
        check("token.error.detected", Net.isTokenRejected(rejected));

        Net.Resp html = new Net.Resp();
        html.code = 403;
        html.bodyPreview = "<html><body>403 Forbidden</body></html>";
        check("token.html.notRejected", !Net.isTokenRejected(html));

        Net.Resp cn = new Net.Resp();
        cn.code = 403;
        cn.bodyPreview = "{\"msg\":\"令牌无效\"}";
        check("token.chinese.detected", Net.isTokenRejected(cn));

        Net.Resp empty = new Net.Resp();
        empty.code = 403;
        check("token.empty.notRejected", !Net.isTokenRejected(empty));
        check("token.null.notRejected", !Net.isTokenRejected(null));
    }

    private static void testSkipPolicy() {
        List<Strategies.Attempt> all = Strategies.build("v2rayNG/2.3.4", Strategies.MODE_AUTO);
        Strategies.Attempt direct = all.get(0);
        Strategies.Attempt socks = all.get(all.size() - 2);
        check("skip.noFailure.keepsDirect", !Strategies.skipDirectAfterNetworkFailure(false, direct));
        check("skip.failure.skipsDirect", Strategies.skipDirectAfterNetworkFailure(true, direct));
        check("skip.failure.keepsProxy", !Strategies.skipDirectAfterNetworkFailure(true, socks));
    }

    private static void testCodecBackwardCompat() {
        char sep = (char) 1;
        // a record written by v1.0: 8 fields, no remembered method
        String old = "https%3A%2F%2Fexample.com%2Fsub" + sep + "%E6%97%A7%E8%AE%A2%E9%98%85" + sep
                + "1" + sep + "100" + sep + "200" + sep + "300" + sep + "400" + sep + "500";
        List<Sub> out = SubCodec.decode(old);
        check("compat.recordKept", out.size() == 1);
        check("compat.url", out.size() == 1 && out.get(0).url.equals("https://example.com/sub"));
        check("compat.name", out.size() == 1 && out.get(0).name.equals("旧订阅"));
        check("compat.numbers", out.size() == 1 && out.get(0).total == 300L && out.get(0).lastQuery == 500L);
        check("compat.methodEmpty", out.size() == 1 && out.get(0).method.isEmpty());
    }

    private static void testModelApply() {
        Sub s = new Sub();
        s.url = "https://panel.example.com:8443/sub?token=xyz";
        s.apply(Userinfo.parse("upload=1; download=2; total=100; expire=0"), "机场A");
        check("model.name.fromTitle", s.displayName().equals("机场A"));
        check("model.host.parse", Sub.hostOf(s.url).equals("panel.example.com:8443"));
        check("model.usedText", s.info().summaryUsed().contains("3%"));

        Sub t = new Sub();
        t.url = "https://panel.example.com/sub";
        t.apply(null, "");
        check("model.noHeader", !t.hasInfo && t.displayName().equals("panel.example.com"));
        check("model.noHeaderText", t.info().summaryRemaining().equals("剩余：不限量"));
    }

    private static void testLiveQuery(String url) {
        System.out.println("--- live query: " + url);
        java.util.List<Strategies.Attempt> attempts = Strategies.build(Net.DEFAULT_UA, Strategies.MODE_DIRECT);
        Userinfo found = null;
        for (int i = 0; i < attempts.size(); i++) {
            Strategies.Attempt a = attempts.get(i);
            Net.Resp r = Net.get(url, a.ua != null ? a.ua : Net.DEFAULT_UA, false, null, 0);
            System.out.println("[" + (i + 1) + "] " + a.label
                    + " -> code=" + r.code + " err=" + r.error
                    + " userinfo=" + (r.header("subscription-userinfo").isEmpty() ? "(none)" : "yes"));
            if (i == 0) {
                System.out.println("    content-type = " + r.header("content-type"));
                System.out.println("    body preview = " + r.bodyPreview);
            }
            Userinfo u = Userinfo.parse(r.header("subscription-userinfo"));
            if (u != null) {
                found = u;
                System.out.println("=> " + u.summaryRemaining());
                System.out.println("=> " + u.summaryUsed());
                System.out.println("=> " + u.summaryExpire());
                System.out.println("=> 命中方式：" + a.label);
                break;
            }
        }
        check("live.header.present", found != null);
    }

    private static void check(String label, boolean ok) {
        if (!ok) {
            failed++;
        }
        System.out.println((ok ? "[ok]   " : "[FAIL] ") + label);
    }
}