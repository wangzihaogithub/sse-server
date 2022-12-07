package com.github.sseserver.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class LambdaUtil {

    public static <E extends Throwable> void sneakyThrows(Throwable t) throws E {
        throw (E) t;
    }

    public static <T> Function<T, T> noop() {
        return o -> o;
    }

    public static Supplier<Boolean> defaultFalse() {
        return () -> Boolean.FALSE;
    }

    public static <T> Supplier<T> defaultNull() {
        return () -> null;
    }

    public static Supplier<Integer> defaultZero() {
        return () -> 0;
    }

    public static <T> BiFunction<T, T, T> filterNull() {
        return (o1, o2) -> o1 != null ? o1 : o2;
    }

    public static <T extends Collection> BiFunction<T, T, T> reduceList() {
        return (o1, o2) -> {
            o1.addAll(o2);
            return o1;
        };
    }

    public static <E, T extends Collection<E>> Function<T, List<E>> distinct() {
        return o -> new ArrayList<>(new LinkedHashSet<>(o));
    }
}
