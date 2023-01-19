package com.github.sseserver.util;

import com.github.sseserver.springboot.SseServerProperties;

import java.util.*;

public class AutoTypeBean {
    private Map<String, Collection<Integer>> arrayClassName;
    private String objectClassName;

    /**
     * 保留类型
     *
     * @param data
     */
    public void retainClassName(Object data) {
        if (data instanceof Collection) {
            int i = 0;
            Map<String, Collection<Integer>> classMap = new HashMap<>(1);
            for (Object item : (Collection) data) {
                if (!TypeUtil.isBasicType(item)) {
                    classMap.computeIfAbsent(item.getClass().getName(), e -> new ArrayList<>())
                            .add(i);
                }
                i++;
            }
            arrayClassName = classMap;
        } else if (TypeUtil.isBasicType(data)) {

        } else {
            objectClassName = data.getClass().getName();
        }
    }

    /**
     * 转换类型
     *
     * @param data
     * @param arrayClassName
     * @param objectClassName
     * @param autoTypeEnum
     * @param classNotFoundCacheSet
     * @param <T>
     * @return
     * @throws ClassNotFoundException
     */
    public static <T> T cast(Object data,
                             Map<String, Collection<Integer>> arrayClassName,
                             String objectClassName,
                             SseServerProperties.AutoType autoTypeEnum, Set<String> classNotFoundCacheSet) throws ClassNotFoundException {
        if (autoTypeEnum == SseServerProperties.AutoType.DISABLED) {
            return (T) data;
        }

        if (objectClassName != null) {
            if (autoTypeEnum == SseServerProperties.AutoType.CLASS_NOT_FOUND_USE_MAP
                    && classNotFoundCacheSet.contains(objectClassName)) {
                return (T) data;
            } else {
                try {
                    return TypeUtil.castBean(data, objectClassName);
                } catch (ClassNotFoundException e) {
                    classNotFoundCacheSet.add(objectClassName);
                    if (autoTypeEnum == SseServerProperties.AutoType.CLASS_NOT_FOUND_THROWS) {
                        throw e;
                    } else {
                        return (T) data;
                    }
                }
            }
        } else if (arrayClassName != null && data instanceof Collection) {
            for (Map.Entry<String, Collection<Integer>> entry : arrayClassName.entrySet()) {
                Collection<Integer> value = entry.getValue();
                if (!(value instanceof Set)) {
                    entry.setValue(new HashSet<>(value));
                }
            }
            List list = new ArrayList(((Collection<?>) data).size());
            int i = 0;
            for (Object item : (Collection) data) {
                for (Map.Entry<String, Collection<Integer>> entry : arrayClassName.entrySet()) {
                    if (entry.getValue().contains(i)) {
                        objectClassName = entry.getKey();
                        break;
                    }
                }
                if (objectClassName == null) {
                    list.add(item);
                } else if (autoTypeEnum == SseServerProperties.AutoType.CLASS_NOT_FOUND_USE_MAP
                        && classNotFoundCacheSet.contains(objectClassName)) {
                    list.add(item);
                } else {
                    try {
                        list.add(TypeUtil.castBean(item, objectClassName));
                    } catch (ClassNotFoundException e) {
                        classNotFoundCacheSet.add(objectClassName);
                        if (autoTypeEnum == SseServerProperties.AutoType.CLASS_NOT_FOUND_USE_MAP) {
                            list.add(item);
                        } else {
                            throw e;
                        }
                    }
                }
                i++;
            }
            return (T) list;
        } else {
            return (T) data;
        }
    }

    public void setArrayClassName(Map<String, Collection<Integer>> arrayClassName) {
        this.arrayClassName = arrayClassName;
    }

    public void setObjectClassName(String objectClassName) {
        this.objectClassName = objectClassName;
    }

    public Map<String, Collection<Integer>> getArrayClassName() {
        return arrayClassName;
    }

    public String getObjectClassName() {
        return objectClassName;
    }
}
