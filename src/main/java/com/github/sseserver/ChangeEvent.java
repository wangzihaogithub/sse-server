package com.github.sseserver;

/**
 * 变化事件
 *
 * @author wangzihaogithub 2022-11-12
 */
public class ChangeEvent<ACCESS_USER, VALUE> {
    private final SseEmitter<ACCESS_USER> instance;
    private final String eventName;
    private final VALUE before;
    private final VALUE after;

    public ChangeEvent(SseEmitter<ACCESS_USER> instance, String eventName, VALUE before, VALUE after) {
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