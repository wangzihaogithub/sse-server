package com.github.sseserver.util;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 比较两个对象的不同之处
 *
 * @author hao
 */
public class DifferentComparatorUtil {

    public static <E> ListDiffResult<E> listDiff(Collection<E> before, Collection<E> after) {
        return listDiff(before, after, e -> e);
    }

    public static <E, ID> ListDiffResult<E> listDiff(Collection<? extends E> before, Collection<? extends E> after, Function<E, ID> idFunction) {
        if (before == null) {
            before = Collections.emptyList();
        }
        if (after == null) {
            after = Collections.emptyList();
        }
        Map<ID, E> leftMap = before.stream()
                .collect(Collectors.toMap(idFunction, e -> e, (o1, o2) -> o1, LinkedHashMap::new));
        Map<ID, E> rightMap = after.stream()
                .collect(Collectors.toMap(idFunction, e -> e, (o1, o2) -> o1, LinkedHashMap::new));

        ListDiffResult<E> result = new ListDiffResult<>();
        for (Map.Entry<ID, E> entry : leftMap.entrySet()) {
            if (rightMap.containsKey(entry.getKey())) {
            } else {
                result.getDeleteList().add(entry.getValue());
            }
        }
        for (Map.Entry<ID, E> entry : rightMap.entrySet()) {
            if (leftMap.containsKey(entry.getKey())) {
            } else {
                result.getInsertList().add(entry.getValue());
            }
        }
        return result;
    }


    public static class ListDiffResult<E> {
        private final List<E> insertList = new ArrayList<>();
        private final List<E> deleteList = new ArrayList<>();

        public List<E> getInsertList() {
            return insertList;
        }

        public List<E> getDeleteList() {
            return deleteList;
        }

        public boolean isEmpty() {
            return insertList.isEmpty() && deleteList.isEmpty();
        }
    }

}
