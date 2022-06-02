package com.github.sseserver.util;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

public class WebUtil {
    public static final String PROTOCOL_HTTPS = "https:";
    public static final String PROTOCOL_HTTP = "http:";
    public static final Pattern PATTERN_HTTP = Pattern.compile(PROTOCOL_HTTP);

    public static String getRequestIpAddr(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果是多级代理，那么取第一个ip为客户ip
        if (ip != null && ip.contains(",")) {
            ip = ip.substring(ip.lastIndexOf(",") + 1).trim();
        }
        return ip;
    }

    public static String getRequestDomain(HttpServletRequest request) {
        return getRequestDomain(request, true);
    }

    public static String getRequestDomain(HttpServletRequest request, boolean appendContextPath) {
        StringBuffer url = request.getRequestURL();
        StringBuffer sb = url
                .delete(url.length() - request.getRequestURI().length(), url.length());
        if (appendContextPath) {
            sb.append(request.getServletContext().getContextPath());
        }
        return rewriteHttpToHttpsIfSecure(sb.toString(), request.isSecure());
    }

    /**
     * 如果当前域名是https的, 则要将http地址改为https
     *
     * @param domain          域名
     * @param isSecureRequest 当前是否是https
     * @return 重写协议后的域名
     */
    public static String rewriteHttpToHttpsIfSecure(String domain, boolean isSecureRequest) {
        if (isSecureRequest) {
            if (domain.startsWith(PROTOCOL_HTTP)) {
                return PATTERN_HTTP.matcher(domain).replaceFirst(PROTOCOL_HTTPS);
            }
        }
        return domain;
    }

}
