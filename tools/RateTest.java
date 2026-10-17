import com.gukangran.subtraffic.Net;

public class RateTest {
    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "PASS  " : "FAIL  ") + name + "  -> " + got);
    }
    static Net.Resp resp(int code, String body) {
        Net.Resp r = new Net.Resp();
        r.code = code;
        r.bodyPreview = body;
        return r;
    }
    public static void main(String[] a) {
        // 429 限流
        check("429 判为限流", Net.isRateLimited(resp(429, "<html>Too Many Requests</html>")), true);
        check("429 不算链接失效", Net.isLinkExpired(resp(429, "Too Many Requests")), false);
        check("429 不算令牌被拒", Net.isTokenRejected(resp(429, "Too Many Requests")), false);

        // 你遇到的那两种情况
        check("404 Subscription link is invalid or expired -> 链接失效",
                Net.isLinkExpired(resp(404, "Subscription link is invalid or expired")), true);
        check("404 invalid or expired 不算令牌被拒",
                Net.isTokenRejected(resp(404, "Subscription link is invalid or expired")), false);

        // 之前误判的 HTML 错误页（里面带 error 字样）
        check("HTML 错误页不算令牌被拒",
                Net.isTokenRejected(resp(429, "<!doctype html> <html> <body> error token key </body> </html>")), false);

        // 真正的令牌失效
        check("token is error 判为令牌被拒", Net.isTokenRejected(resp(403, "{\"message\": \"token is error\"}")), true);
        check("token is error 不算链接失效", Net.isLinkExpired(resp(403, "{\"message\": \"token is error\"}")), false);

        // 正常页面不应误判
        check("正常 200 不误判",
                Net.isLinkExpired(resp(200, "proxies: []")) || Net.isTokenRejected(resp(200, "proxies: []")), false);
    }
}
