package com.github.sseserver.local;

import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionIDGenerator {
    private static final AtomicInteger ID_INCR = new AtomicInteger();

    private static int newIdInt() {
        int id = ID_INCR.getAndIncrement();
        if (id == Integer.MAX_VALUE) {
            id = 0;
            ID_INCR.set(1);
        }
        return id;
    }

    /**
     * int范围外实例ID
     * int范围内单机ID
     *
     * @return 分布式唯一ID
     */
    public long newId() {
        int idInt = newIdInt();
        return idInt;
    }
}
