package com.github.sseserver.local;

/**
 * 变化事件
 *
 * @author wangzihaogithub 2022-11-12
 */
public class SseChangeEvent<ACCESS_USER, VALUE> {
    public static final String EVENT_ADD_LISTENER = "addListener";
    public static final String EVENT_REMOVE_LISTENER = "removeListener";

    private final SseEmitter<ACCESS_USER> instance;
    private final String eventName;
    private final VALUE before;
    private final VALUE after;

    public SseChangeEvent(SseEmitter<ACCESS_USER> instance, String eventName, VALUE before, VALUE after) {
        this.instance = instance;
        this.eventName = eventName;
        this.before = before;
        this.after = after;
    }

    public SseEmitter<ACCESS_USER> getInstance() {
        return instance;
    }

    public String getEventName() {
        return eventName;
    }

    public VALUE getBefore() {
        return before;
    }

    public VALUE getAfter() {
        return after;
    }
}