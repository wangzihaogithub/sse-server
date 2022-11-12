package com.github.sseserver.qos;

import com.github.sseserver.SseEmitter;

import java.util.List;

public class Delivered<ACCESS_USER> {
    private long startTimestamp;
    private long endTimestamp;
    private List<SseEmitter<ACCESS_USER>> succeedList;

    public long getCostMs() {
        return endTimestamp - startTimestamp;
    }

    public List<SseEmitter<ACCESS_USER>> getSucceedList() {
        return succeedList;
    }

    public void setSucceedList(List<SseEmitter<ACCESS_USER>> succeedList) {
        this.succeedList = succeedList;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }
}
