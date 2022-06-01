package com.github.sseserver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息事件推送 (非分布式)
 * 注: !! 这里是示例代码, 根据自己项目封装的用户逻辑, 继承类或复制到自己项目里都行
 * <p>
 * 1. 如果用nginx代理, 要加下面的配置
 * # 长连接配置
 * proxy_buffering off;
 * proxy_read_timeout 7200s;
 * proxy_pass http://xx.xx.xx.xx:xxx;
 * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
 * proxy_set_header Connection ""; # 去掉Connection的close字段
 *
 * @author hao 2021年12月7日19:29:51
 */
//@RestController
//@RequestMapping("/a/sse")
//@RequestMapping("/b/sse")
public class SseWebController<ACCESS_USER extends AccessUser & AccessToken> {
    protected LocalConnectionService localConnectionService;

    /**
     * 前端文件
     */
    @RequestMapping("")
    public Object index() {
        return ssejs();
    }

    /**
     * 前端文件
     */
    @RequestMapping("/sse.js")
    public Object ssejs() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/javascript;charset=utf-8");
        InputStream stream = SseWebController.class.getResourceAsStream("/sse.js");
        Resource body = new InputStreamResource(stream);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    public void setLocalConnectionService(LocalConnectionService localConnectionService) {
        this.localConnectionService = localConnectionService;
    }

    @Autowired(required = false)
    public void setLocalConnectionServiceMap(Map<String, LocalConnectionService> localConnectionServiceMap) {
        if (this.localConnectionService == null && localConnectionServiceMap != null && localConnectionServiceMap.size() > 0) {
            this.localConnectionService = localConnectionServiceMap.values().iterator().next();
        }
    }


    /**
     * 获取当前登录用户, 这里返回后, 就可以获取 {@link SseEmitter#getAccessUser()}
     *
     * @return 使用者自己系统的用户
     */
    protected ACCESS_USER getAccessUser() {
        return null;
    }

    protected Object wrapOkResponse(Object result) {
        return new ResponseWrap<>(result);
    }

    protected void onConnect(SseEmitter<ACCESS_USER> conncet, Map<String, Object> query) {

    }

    protected void onDisconnect(List<SseEmitter<ACCESS_USER>> disconnectList, ACCESS_USER accessUser, Map query) {

    }

    protected ResponseEntity buildIfConnectVerifyErrorResponse(ACCESS_USER accessUser,
                                                               Map query, Map body,
                                                               Long keepaliveTime, HttpServletRequest request) {
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        return null;
    }

    protected ResponseEntity buildUnauthorizedResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setConnection("close");
        return new ResponseEntity<>("", headers, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 创建连接
     */
    @RequestMapping("/connect")
    public Object connect(@RequestParam Map query, @RequestBody(required = false) Map body,
                          Long keepaliveTime, HttpServletRequest request) {
        Map<String, Object> message = new LinkedHashMap<>(query);
        if (body != null) {
            message.putAll(body);
        }
        ACCESS_USER accessUser = getAccessUser();
        ResponseEntity responseEntity = buildIfConnectVerifyErrorResponse(accessUser, query, body, keepaliveTime, request);
        if (responseEntity != null) {
            return responseEntity;
        }
        SseEmitter<ACCESS_USER> emitter = localConnectionService.connect(accessUser, keepaliveTime, message);

        String channel = Objects.toString(message.get("channel"), null);
        emitter.setChannel(isBlank(channel) ? null : channel);

        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String, String> headerMap = new LinkedHashMap<>();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headerMap.put(name, request.getHeader(name));
        }
        emitter.setAttribute("httpHeaders", headerMap);
        emitter.setAttribute("httpCookies", request.getCookies());
        emitter.setAttribute("httpParameters", new LinkedHashMap<>(request.getParameterMap()));
        onConnect(emitter, message);
        return emitter;
    }

    /**
     * 推送给所有人
     *
     * @return http原生响应
     */
    @RequestMapping("/send")
    public ResponseEntity send(@RequestParam Map query, @RequestBody(required = false) Map body) {
        Map message = new LinkedHashMap<>(query);
        if (body != null) {
            message.putAll(body);
        }
        int count = localConnectionService.sendAll(buildEvent(message));
        return ResponseEntity.ok(wrapOkResponse((Collections.singletonMap("count", count))));
    }

    /**
     * 发送给单个人
     *
     * @param userId 用户ID
     * @return http原生响应
     */
    @RequestMapping("/send/{userId}")
    public ResponseEntity sendOne(@RequestParam Map query, @RequestBody(required = false) Map body, @PathVariable Object userId) {
        Map message = new LinkedHashMap<>(query);
        if (body != null) {
            message.putAll(body);
        }
        int count = localConnectionService.sendByUserId(userId, buildEvent(message));
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", count)));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect/{connectionId}")
    public ResponseEntity disconnect(@PathVariable Long connectionId, @RequestParam Map query) {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
        if (disconnect != null) {
            onDisconnect(Collections.singletonList(disconnect), accessUser, query);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnect != null ? 1 : 0)));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect")
    public ResponseEntity disconnect0(Long connectionId, @RequestParam Map query) {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        String accessToken = accessUser.getAccessToken();
        if (connectionId != null) {
            SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnectByConnectionId(connectionId);
            if (disconnect != null) {
                onDisconnect(Collections.singletonList(disconnect), accessUser, query);
            }
            return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnect != null ? 1 : 0)));
        } else {
            List<SseEmitter<ACCESS_USER>> count = localConnectionService.disconnectByAccessToken(accessToken);
            if (count.size() > 0) {
                onDisconnect(count, accessUser, query);
            }
            return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", count.size())));
        }
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnectUser")
    public ResponseEntity disconnectUser(String userId, @RequestParam Map query) {
        ACCESS_USER accessUser = getAccessUser();
        if (accessUser == null) {
            return buildUnauthorizedResponse();
        }
        List<SseEmitter<ACCESS_USER>> disconnectList = localConnectionService.disconnectByUserId(userId);
        if (disconnectList.size() > 0) {
            onDisconnect(disconnectList, accessUser, query);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnectList.size())));
    }

    @RequestMapping("/users")
    public Object users(@RequestParam(required = false, defaultValue = "1") Integer pageNum,
                        @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                        String name) {
        List<? extends AccessUser> list = localConnectionService.getUsers();
        String nameTrim = name != null ? name.trim() : null;
        if (name != null && nameTrim.length() > 0) {
            list = list.stream()
                    .filter(e -> {
                        String eachName = e.getName();
                        if (eachName == null) {
                            return false;
                        }
                        return eachName.contains(nameTrim);
                    })
                    .collect(Collectors.toList());
        }
        return ResponseEntity.ok(wrapOkResponse(PageInfo.of(list, pageNum, pageSize)));
    }

    @RequestMapping("/connectionIds")
    public Object connectionIds() {
        return ResponseEntity.ok(wrapOkResponse(localConnectionService.getConnectionIds()));
    }

    private SseEmitter.SseEventBuilder buildEvent(Map rawMessage) {
        Map message = new LinkedHashMap(rawMessage);
        SseEmitter.SseEventBuilder event = SseEmitter.event();
        Object id = message.remove("id");
        if (id != null) {
            event.id(id.toString());
        }
        Object name = message.remove("name");
        if (name != null) {
            event.name(name.toString());
        }
        Object comment = message.remove("comment");
        if (comment != null) {
            event.comment(comment.toString());
        }
        Object reconnectTime = message.remove("reconnectTime");
        if (reconnectTime != null) {
            event.reconnectTime(Long.parseLong(reconnectTime.toString()));
        }
        if (!message.isEmpty()) {
            event.data(message);
        }
        return event;
    }

    public boolean isBlank(CharSequence str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if ((!Character.isWhitespace(str.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    public static class ResponseWrap<T> implements Serializable {

        /**
         * 请求是否成功
         */
        private boolean success = true;
        /**
         * 成功或者失败的code错误码
         */
        private int code = 200;
        /**
         * 成功时返回的数据，失败时返回具体的异常信息
         */
        private T data;
        /**
         * 请求失败返回的提示信息，给前端进行页面展示的信息
         */
        private String message;
        /**
         * 请求失败返回的提示信息，排查用的信息
         */
        private String errorMessage;

        public ResponseWrap() {
        }

        public ResponseWrap(T data) {
            this.data = data;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
