package com.github.sseserver.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class BeanUtil {
    private static final Map<Class, Constructor> CONSTRUCTOR_NO_ARG_MAP = new LinkedHashMap<Class, Constructor>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > 300;
        }
    };
    private static final Class[] EMPTY_CLASS_ARRAY = {};
    private static final Object[] EMPTY_OBJECT_ARRAY = {};
    private static final Method UNSAFE_ALLOCATE_INSTANCE_METHOD;
    private static final Object UNSAFE;

    static {
        Method unsafeAllocateInstanceMethod;
        Object unsafe;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            unsafeAllocateInstanceMethod = unsafeClass.getDeclaredMethod("allocateInstance", Class.class);
        } catch (Throwable e) {
            unsafe = null;
            unsafeAllocateInstanceMethod = null;
        }
        UNSAFE = unsafe;
        UNSAFE_ALLOCATE_INSTANCE_METHOD = unsafeAllocateInstanceMethod;
    }

    public static <T> T newInstance(String typeString) {
        Class<T> type;
        try {
            type = (Class<T>) Class.forName(typeString);
        } catch (Exception e) {
            throw new IllegalStateException("newInstance(" + typeString + ") fail : " + e, e);
        }
        return newInstance(type);
    }

    public static <T> T newInstance(Class<T> type) {
        Constructor constructor = CONSTRUCTOR_NO_ARG_MAP.computeIfAbsent(type, o -> {
            try {
                return o.getConstructor(EMPTY_CLASS_ARRAY);
            } catch (Exception e) {
                return null;
            }
        });

        if (constructor == null) {
            boolean isJavaPackage = Optional.ofNullable(type.getPackage()).map(Package::getName).map(o -> o.startsWith("java.")).orElse(true);
            if (UNSAFE != null && !Modifier.isAbstract(type.getModifiers()) && !isJavaPackage) {
                try {
                    return (T) UNSAFE_ALLOCATE_INSTANCE_METHOD.invoke(UNSAFE, type);
                } catch (Throwable ignored) {
                }
            }
            throw new IllegalStateException("Can not newInstance(). class=" + type);
        }

        constructor.setAccessible(true);
        try {
            return (T) constructor.newInstance(EMPTY_OBJECT_ARRAY);
        } catch (Exception e) {
            boolean isJavaPackage = Optional.ofNullable(type.getPackage()).map(Package::getName).map(o -> o.startsWith("java.")).orElse(true);
            if (UNSAFE != null && !Modifier.isAbstract(type.getModifiers()) && !isJavaPackage) {
                try {
                    return (T) UNSAFE_ALLOCATE_INSTANCE_METHOD.invoke(UNSAFE, type);
                } catch (Throwable ignored) {
                }
            }
            throw new IllegalStateException("Can not newInstance(). e=" + e + ",class=" + type + ",constructor=" + constructor, e);
        }
    }
}
