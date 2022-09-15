package com.github.sseserver.util;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class WebUtil {
    public static final String PROTOCOL_HTTPS = "https:";
    public static final String PROTOCOL_HTTP = "http:";
    public static final Pattern PATTERN_HTTP = Pattern.compile(PROTOCOL_HTTP);
    private static String ipAddress;

    /**
     * 是否是有效版本
     *
     * @param requestVersion 用户用的版本号
     * @param minVersion     要求的最小版本 (传空就是不控制,全部有效)
     * @return true=有效,大于等于minVersion。 false=无效版本，小于minVersion
     */
    public static boolean isInVersion(String requestVersion, String minVersion) {
        // 限制最低使用版本 (1.1.6)
        Integer[] pluginVersionNumbers = WebUtil.parseNumber(requestVersion);
        Integer[] minVersionNumbers = WebUtil.parseNumber(minVersion);
        for (int i = 0; i < pluginVersionNumbers.length && i < minVersionNumbers.length; i++) {
            int min = minVersionNumbers[i];
            int curr = pluginVersionNumbers[i];
            if (curr > min) {
                return true;
            } else if (curr == min) {
                // 继续比较
            } else {
                return false;
            }
        }
        return true;
    }

    public static Integer[] parseNumber(String str) {
        if (str == null) {
            return new Integer[0];
        }
        List<Integer> result = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            } else if (builder.length() > 0) {
                try {
                    result.add(Integer.valueOf(builder.toString()));
                } catch (NumberFormatException e) {

                }
                builder.setLength(0);
            }
        }
        if (builder.length() > 0) {
            try {
                result.add(Integer.valueOf(builder.toString()));
            } catch (NumberFormatException e) {

            }
        }
        return result.toArray(new Integer[0]);
    }

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

    public static String getIPAddress(Integer port) {
        if (port != null && port > 0) {
            return getIPAddress() + ":" + port;
        } else {
            return getIPAddress();
        }
    }

    public static String getIPAddress() {
        if (ipAddress != null) {
            return ipAddress;
        } else {
            try {
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                String[] skipNames = {"TAP", "VPN", "UTUN", "VIRBR"};
                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = networkInterfaces.nextElement();
                    if (networkInterface.isVirtual() && networkInterface.isLoopback()) {
                        continue;
                    }

                    String name = Objects.toString(networkInterface.getName(), "").trim().toUpperCase();
                    String displayName = Objects.toString(networkInterface.getDisplayName(), "").trim().toUpperCase();
                    String netName = name.length() > 0 ? name : displayName;
                    boolean skip = Stream.of(skipNames).anyMatch(netName::contains);
                    if (skip) {
                        continue;
                    }

                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        if (inetAddress.isLoopbackAddress() || !inetAddress.isSiteLocalAddress()) {
                            continue;
                        }
                        String hostAddress = inetAddress.getHostAddress();
                        return ipAddress = hostAddress;
                    }
                }
                // 如果没有发现 non-loopback地址.只能用最次选的方案
                return ipAddress = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception var6) {
                return null;
            }
        }
    }

    public static void main(String[] args) {
        getIPAddress();
    }

}
