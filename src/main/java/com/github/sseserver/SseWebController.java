package com.github.sseserver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.Serializable;
import java.util.*;

/**
 * 消息事件推送 (非分布式)
 * 注: !! 这里是示例代码, 根据自己项目封装的用户逻辑, 复制到自己项目里
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
//@RequestMapping("/api/sse")
public class SseWebController<ACCESS_USER extends AccessUser & AccessToken> {
    private LocalConnectionService localConnectionService;

    @Autowired(required = false)
    public void setLocalConnectionService(LocalConnectionService localConnectionService) {
        if (this.localConnectionService == null) {
            this.localConnectionService = localConnectionService;
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

    protected void onConnect(SseEmitter<ACCESS_USER> conncet) {

    }

    protected void onDisconnect(List<SseEmitter<ACCESS_USER>> disconnectList, ACCESS_USER accessUser, String accessToken, Long connectionId) {

    }

    /**
     * 创建连接
     */
    @RequestMapping("/connect")
    public SseEmitter connect(@RequestParam Map query, @RequestBody(required = false) Map body,
                              Long keepaliveTime) {
        Map message = new LinkedHashMap<>(query);
        if (body != null) {
            message.putAll(body);
        }
        ACCESS_USER accessUser = getAccessUser();
        SseEmitter<ACCESS_USER> emitter = localConnectionService.connect(accessUser, keepaliveTime);
        emitter.getAttributeMap().putAll(message);

        String channel = Objects.toString(message.get("channel"), null);
        emitter.setChannel(isBlank(channel) ? null : channel);
        onConnect(emitter);
        return emitter;
    }

    /**
     * 推送给所有人
     *
     * @return
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
     * @param accessToken
     * @return
     */
    @RequestMapping("/send/{accessToken}")
    public ResponseEntity sendOne(@RequestParam Map query, @RequestBody(required = false) Map body, @PathVariable String accessToken) {
        Map message = new LinkedHashMap<>(query);
        if (body != null) {
            message.putAll(body);
        }
        int count = localConnectionService.send(accessToken, buildEvent(message));
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", count)));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect/{connectionId}")
    public ResponseEntity disconnect(@PathVariable Long connectionId) {
        ACCESS_USER accessUser = getAccessUser();
        String accessToken = accessUser.getAccessToken();
        SseEmitter<ACCESS_USER> disconnect = localConnectionService.disconnect(accessToken, connectionId);
        if (disconnect != null) {
            onDisconnect(Collections.singletonList(disconnect), accessUser, accessToken, connectionId);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", disconnect != null ? 1 : 0)));
    }

    /**
     * 关闭连接
     */
    @RequestMapping("/disconnect")
    public ResponseEntity disconnect() {
        ACCESS_USER accessUser = getAccessUser();
        String accessToken = accessUser.getAccessToken();
        List<SseEmitter<ACCESS_USER>> count = localConnectionService.disconnect(accessToken);
        if (count.size() > 0) {
            onDisconnect(count, accessUser, accessToken, null);
        }
        return ResponseEntity.ok(wrapOkResponse(Collections.singletonMap("count", count.size())));
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
