package com.github.sseserver.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class WebUtil {
    private static final String PROTOCOL_HTTPS = "https:";
    private static final String PROTOCOL_HTTP = "http:";
    private static final Pattern PATTERN_HTTP = Pattern.compile(PROTOCOL_HTTP);
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    public static Integer port;
    private static String ipAddress;

    /**
     * 是否是有效版本
     *
     * @param requestVersion 用户用的版本号
     * @param minVersion     要求的最小版本 (传空就是不控制,全部有效)
     * @return true=有效,大于等于minVersion。 false=无效版本，小于minVersion
     */
    public static boolean isInVersion(String requestVersion, String minVersion) {
        // 限制最低使用版本 (1.1.7)
        Integer[] pluginVersionNumbers = parseNumber(requestVersion);
        Integer[] minVersionNumbers = parseNumber(minVersion);
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

    private static Integer[] parseNumber(String str) {
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
                        if (inetAddress.isLoopbackAddress() || !inetAddress.isSiteLocalAddress() || !inetAddress.isReachable(100)) {
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

    public static String getQueryParam(String uriQuery, String findName) {
        int len = uriQuery.length();
        int nameStart = 0;
        int valueStart = -1;
        int i;
        loop:
        for (i = 0; i < len; i++) {
            switch (uriQuery.charAt(i)) {
                case '=':
                    if (nameStart == i) {
                        nameStart = i + 1;
                    } else if (valueStart < nameStart) {
                        valueStart = i + 1;
                    }
                    break;
                case '&':
                case ';':
                    if (nameStart < i) {
                        int valueStart0 = valueStart;
                        if (valueStart0 <= nameStart) {
                            valueStart0 = i + 1;
                        }
                        String name = decodeComponent(uriQuery, nameStart, valueStart0 - 1, UTF_8);
                        if (findName.equals(name)) {
                            return decodeComponent(uriQuery, valueStart0, i, UTF_8);
                        }
                    }
                    nameStart = i + 1;
                    break;
                case '#':
                    break loop;
                default:
                    // continue
            }
        }
        if (nameStart < i) {
            int valueStart0 = valueStart;
            if (valueStart0 <= nameStart) {
                valueStart0 = i + 1;
            }
            String name = decodeComponent(uriQuery, nameStart, valueStart0 - 1, UTF_8);
            if (findName.equals(name)) {
                return decodeComponent(uriQuery, valueStart0, i, UTF_8);
            }
        }
        return null;
    }

    public static String decodeComponent(String s, int from, int toExcluded, Charset charset) {
        int len = toExcluded - from;
        if (len <= 0) {
            return "";
        }
        int firstEscaped = -1;
        for (int i = from; i < toExcluded; i++) {
            char c = s.charAt(i);
            if (c == '%' || c == '+') {
                firstEscaped = i;
                break;
            }
        }
        if (firstEscaped == -1) {
            return s.substring(from, toExcluded);
        }

        // Each encoded byte takes 3 characters (e.g. "%20")
        int decodedCapacity = (toExcluded - firstEscaped) / 3;
        if (decodedCapacity == 0) {
            return s.substring(from, toExcluded);
        }
        byte[] buf = new byte[decodedCapacity];
        int bufIdx;

        StringBuilder strBuf = new StringBuilder(len);
        strBuf.append(s, from, firstEscaped);


        for (int i = firstEscaped; i < toExcluded; i++) {
            char c = s.charAt(i);
            if (c != '%') {
                strBuf.append(c != '+' ? c : ' ');
                continue;
            }

            bufIdx = 0;
            do {
                if (i + 3 > toExcluded) {
                    return s.substring(from, toExcluded);
//                    throw new IllegalArgumentException("unterminated escape sequence at index " + i + " of: " + s);
                }
                int hi = decodeHexNibble(s.charAt(i + 1));
                int lo = decodeHexNibble(s.charAt(i + 2));
                if (hi != -1 && lo != -1) {
                    buf[bufIdx++] = (byte) ((hi << 4) + lo);
                } else {
                    return s.substring(from, toExcluded);
//                    throw new IllegalArgumentException(String.format("invalid hex byte '%s' at index %d of '%s'", s.subSequence(pos, pos + 2), pos, s));
                }

                i += 3;
            } while (i < toExcluded && s.charAt(i) == '%');
            i--;

            strBuf.append(new String(buf, 0, bufIdx, charset));
        }
        return strBuf.toString();
    }

    private static int decodeHexNibble(final char c) {
        // Character.digit() is not used here, as it addresses a larger
        // set of characters (both ASCII and full-width latin letters).
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - ('A' - 0xA);
        }
        if (c >= 'a' && c <= 'f') {
            return c - ('a' - 0xA);
        }
        return -1;
    }
}
