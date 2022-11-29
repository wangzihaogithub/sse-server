package com.github.sseserver.util;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Locale.ENGLISH;

/**
 * bean可以是map, 也可以是bean
 * 如果bean的数据变化, 这个map的数据也会变化
 * 可以让bean继承这个map
 *
 * @author acer01 2019年11月25日 12:03:39
 */
public class BeanMap extends LinkedHashMap<String, Object> {
    private static final Map<Class, Map<String, FieldPropertyDescriptor>> FIELD_DESCRIPTOR_CACHE = new SoftHashMap<>();
    private static final Map<Class, Map<String, PropertyDescriptor>> DESCRIPTOR_CACHE = new SoftHashMap<>();
    private static final Pattern LINE_PATTERN = Pattern.compile("_(\\w)");
    private static final Pattern HUMP_PATTERN = Pattern.compile("[A-Z]");
    private final Map<String, FieldPropertyDescriptor> fieldDescriptorMap;
    private final Map<String, PropertyDescriptor> descriptorMap;
    private Object bean;

    public BeanMap(Map map) {
        this();
        putAll(map);
    }

    public BeanMap(Object bean) {
        this.bean = bean;
        this.fieldDescriptorMap = findPropertyFieldDescriptor(bean.getClass());
        this.descriptorMap = findPropertyDescriptor(bean.getClass());
        for (String name : descriptorMap.keySet()) {
            super.put(name, null);
        }
    }

    public BeanMap(Object bean, Object... keyValues) {
        this.bean = bean;
        this.fieldDescriptorMap = findPropertyFieldDescriptor(bean.getClass());
        this.descriptorMap = findPropertyDescriptor(bean.getClass());
        for (String name : descriptorMap.keySet()) {
            super.put(name, null);
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            put((String) keyValues[i], keyValues[i + 1]);
        }
    }

    public BeanMap() {
        this.bean = this;
        this.fieldDescriptorMap = findPropertyFieldDescriptor(bean.getClass());
        this.descriptorMap = findPropertyDescriptor(bean.getClass());
        for (String name : descriptorMap.keySet()) {
            super.put(name, null);
        }
    }

    public BeanMap(Class type) {
        this.bean = this;
        this.fieldDescriptorMap = findPropertyFieldDescriptor(type);
        this.descriptorMap = findPropertyDescriptor(type);
        for (String name : descriptorMap.keySet()) {
            super.put(name, null);
        }
    }

    public static Map toMap(Object bean) {
        if (bean instanceof Map) {
            return (Map) bean;
        } else if (bean == null) {
            return new BeanMap();
        } else {
            return new BeanMap(bean);
        }
    }

    /**
     * 下划线转驼峰
     *
     * @param str 字符串
     * @return 驼峰
     */
    public static String lineToHump(String str) {
        str = str.toLowerCase();
        Matcher matcher = LINE_PATTERN.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 驼峰转下划线,效率比上面高
     *
     * @param str 字符串
     * @return 下划线
     */
    public static String humpToLine(String str) {
        Matcher matcher = HUMP_PATTERN.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "_" + matcher.group(0).toLowerCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, PropertyDescriptor> findPropertyDescriptor(Class<?> beanClass) {
        if (beanClass == null || beanClass.isInterface() || beanClass.isArray() || beanClass.isPrimitive()) {
            return Collections.emptyMap();
        }
        Map<String, PropertyDescriptor> map = DESCRIPTOR_CACHE.get(beanClass);
        if (map == null) {
            map = Collections.unmodifiableMap(resolvePropertyDescriptor(beanClass));
            DESCRIPTOR_CACHE.put(beanClass, map);
        }
        return map;
    }

    public static Map<String, FieldPropertyDescriptor> findPropertyFieldDescriptor(Class<?> beanClass) {
        if (beanClass == null || beanClass.isInterface() || beanClass.isArray() || beanClass.isPrimitive()) {
            return Collections.emptyMap();
        }
        Map<String, FieldPropertyDescriptor> map = FIELD_DESCRIPTOR_CACHE.get(beanClass);
        if (map == null) {
            map = Collections.unmodifiableMap(resolvePropertyFieldDescriptor(beanClass));
            FIELD_DESCRIPTOR_CACHE.put(beanClass, map);
        }
        return map;
    }

    public static List<Field> findFieldList(Class<?> beanClass) {
        return findPropertyFieldDescriptor(beanClass).values().stream()
                .map(FieldPropertyDescriptor::getField)
                .collect(Collectors.toList());
    }

    private static Map<String, Method> findSetterMethods(Method[] methods) {
        String prefix = "set";
        Set<Class> excludeClasses = new HashSet<>();
        for (Class type = BeanMap.class; type != Object.class; type = type.getSuperclass()) {
            excludeClasses.add(type);
        }

        Map<String, Method> methodMap = new LinkedHashMap<>();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            if (excludeClasses.contains(method.getDeclaringClass())) {
                continue;
            }
            String name = method.getName();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String eachFieldName = Introspector.decapitalize(name.substring(prefix.length()));
            methodMap.put(eachFieldName, method);
        }
        return methodMap;
    }

    private static Map<String, Method> findGetterMethods(Method[] methods) {
        String[] prefixs = {"get", "is"};
        Set<Class> excludeClasses = new HashSet<>();
        for (Class type = BeanMap.class; type != Object.class; type = type.getSuperclass()) {
            excludeClasses.add(type);
        }

        Map<String, Method> methodMap = new LinkedHashMap<>();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (excludeClasses.contains(method.getDeclaringClass())) {
                continue;
            }
            if (method.getReturnType() == void.class) {
                continue;
            }
            String name = method.getName();
            for (String prefix : prefixs) {
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String eachFieldName = Introspector.decapitalize(name.substring(prefix.length()));
                methodMap.put(eachFieldName, method);
            }
        }
        return methodMap;
    }

    private static Map<String, PropertyDescriptor> resolvePropertyDescriptor(Class<?> type) {
        Method[] methods = type.getMethods();
        Map<String, Method> getterMethods = findGetterMethods(methods);
        Map<String, Method> setterMethods = findSetterMethods(methods);
        Map<String, FieldPropertyDescriptor> fields = findPropertyFieldDescriptor(type);

        Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        fieldNames.addAll(fields.keySet());
        fieldNames.addAll(getterMethods.keySet());
        fieldNames.addAll(setterMethods.keySet());
        for (String fieldName : fieldNames) {
            Method getterMethod = getterMethods.get(fieldName);
            Method setterMethod = setterMethods.get(fieldName);
            FieldPropertyDescriptor field = fields.get(fieldName);
            if (field != null) {
                result.put(fieldName, field);
            } else {
                try {
                    result.put(fieldName, new PropertyDescriptor(fieldName, getterMethod, setterMethod));
                } catch (IntrospectionException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    private static Map<String, FieldPropertyDescriptor> resolvePropertyFieldDescriptor(Class<?> type) {
        Class<?> clazz = type;
        Set<String> fieldNames = new LinkedHashSet<>();
        Map<String, FieldPropertyDescriptor> result = new LinkedHashMap<>();
        Stack<List<Field>> classStack = new Stack<>();
        while (clazz != Object.class && clazz != BeanMap.class) {
            List<Field> stack = new ArrayList<>();
            Field[] fields = clazz.getDeclaredFields();
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                // 相同字段子类优先
                if (fieldNames.contains(f.getName())) {
                    continue;
                }
                fieldNames.add(f.getName());
                stack.add(f);
            }
            classStack.push(stack);
            clazz = clazz.getSuperclass();
        }

        while (!classStack.isEmpty()) {
            List<Field> fieldList = classStack.pop();
            for (Field field : fieldList) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                String setterName = "set" + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
                String getterName;
                boolean isBoolean = field.getType() == boolean.class;
                if (isBoolean) {
                    getterName = "is" + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
                } else {
                    getterName = "get" + name.substring(0, 1).toUpperCase(ENGLISH) + name.substring(1);
                }
                try {
                    result.put(name, new FieldPropertyDescriptor(field, type, getterName, setterName));
                } catch (IntrospectionException e) {
                    try {
                        result.put(name, new FieldPropertyDescriptor(field, type, getterName, null));
                    } catch (IntrospectionException e1) {
                        try {
                            result.put(name, new FieldPropertyDescriptor(field, type, null, setterName));
                        } catch (IntrospectionException ex) {
                            try {
                                result.put(name, new FieldPropertyDescriptor(field));
                            } catch (IntrospectionException ignored) {

                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    public static <V extends PropertyDescriptor> V getPropertyDescriptor(Map<?, V> descriptorMap, Object key) {
        V descriptor = descriptorMap.get(key);
        if (descriptor == null && key instanceof String) {
            String keyString = (String) key;
            // 下划线转驼峰
            keyString = lineToHump(keyString);
            descriptor = descriptorMap.get(keyString);

            if (descriptor == null) {
                // 驼峰转下划线
                keyString = humpToLine(keyString);
                descriptor = descriptorMap.get(keyString);
            }
        }
        return descriptor;
    }

    public static Field getField(PropertyDescriptor descriptor) {
        return descriptor instanceof FieldPropertyDescriptor ? ((FieldPropertyDescriptor) descriptor).getField() : null;
    }

    public static Annotation[] getFieldDeclaredAnnotations(PropertyDescriptor descriptor) {
        return descriptor instanceof FieldPropertyDescriptor ? ((FieldPropertyDescriptor) descriptor).getDeclaredAnnotations() : null;
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        Object v = get(key);
        if (v == null) {
            v = put(key, value);
        }
        return v;
    }

    public FieldPropertyDescriptor getFieldPropertyDescriptor(Object key) {
        return getPropertyDescriptor(fieldDescriptorMap, key);
    }

    public PropertyDescriptor getPropertyDescriptor(Object key) {
        return getPropertyDescriptor(descriptorMap, key);
    }

    public Class<?> getPropertyType(Object key) {
        PropertyDescriptor descriptor = getPropertyDescriptor(key);
        return descriptor != null ? descriptor.getPropertyType() : null;
    }

    public Method getReadMethod(Object key) {
        PropertyDescriptor descriptor = getPropertyDescriptor(key);
        return descriptor != null ? descriptor.getReadMethod() : null;
    }

    public Method getWriteMethod(Object key) {
        PropertyDescriptor descriptor = getPropertyDescriptor(key);
        return descriptor != null ? descriptor.getWriteMethod() : null;
    }

    public Annotation[] getFieldDeclaredAnnotations(Object key) {
        FieldPropertyDescriptor descriptor = getFieldPropertyDescriptor(key);
        return descriptor != null ? descriptor.getDeclaredAnnotations() : null;
    }

    public Field getField(Object key) {
        FieldPropertyDescriptor descriptor = getFieldPropertyDescriptor(key);
        return descriptor != null ? descriptor.getField() : null;
    }

    public List<String> getTransientFields() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, FieldPropertyDescriptor> entry : fieldDescriptorMap.entrySet()) {
            if (Modifier.isTransient(entry.getValue().getField().getModifiers())) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    @Override
    public Object remove(Object key) {
        if (key instanceof String) {
            set((String) key, null);
        }
        return super.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (key instanceof String) {
            Object oldValue = get(key);
            if (Objects.equals(oldValue, value)) {
                remove(key);
                return true;
            } else {
                return false;
            }
        }
        return super.remove(key, value);
    }

    public int removeAll(Iterable<String> keys) {
        int count = 0;
        for (String key : keys) {
            if (null != remove(key)) {
                count++;
            }
        }
        return count;
    }

    public Map<String, PropertyDescriptor> getDescriptorMap() {
        return descriptorMap;
    }

    public Map<String, FieldPropertyDescriptor> getFieldDescriptorMap() {
        return fieldDescriptorMap;
    }

    public Object getBean() {
        return bean;
    }

    public void setBean(Object bean) {
        this.bean = bean;
    }

    @Override
    public Object get(Object key) {
        if (key == null) {
            return super.get(null);
        }
        if (bean instanceof Map && !(bean instanceof BeanMap)) {
            return ((Map) bean).get(key);
        }
        try {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(key);
            if (propertyDescriptor != null) {
                Method readMethod = propertyDescriptor.getReadMethod();
                if (readMethod != null) {
                    try {
                        return readMethod.invoke(bean);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }

                Field field = getField(propertyDescriptor);
                if (field != null) {
                    field.setAccessible(true);
                    return field.get(bean);
                } else {
                    return super.get(key);
                }
            } else {
                return super.get(key);
            }
        } catch (Exception e) {
            return super.get(key);
        }
    }

    protected Object cast(Object value, Class type) {
        return TypeUtil.cast(value, type);
    }

    public boolean set(String key, Object value) {
        if (bean instanceof Map && !(bean instanceof BeanMap)) {
            ((Map) bean).put(key, value);
            super.put(key, value);
            return true;
        }
        try {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(key);
            if (propertyDescriptor != null) {
                Method writeMethod = propertyDescriptor.getWriteMethod();
                if (writeMethod != null) {
                    try {
                        Object castValue = cast(value, propertyDescriptor.getPropertyType());
                        writeMethod.invoke(bean, castValue);
                        return true;
                    } catch (Exception ignored) {

                    }
                }
                Field field = getField(propertyDescriptor);
                if (field != null && !Modifier.isFinal(field.getModifiers())) {
                    Object castValue = cast(value, field.getType());
                    field.setAccessible(true);
                    field.set(bean, castValue);
                    return true;
                } else {
                    super.put(key, value);
                }
            } else {
                super.put(key, value);
            }
        } catch (Exception e) {
            super.put(key, value);
        }
        return false;
    }

    @Override
    public Object put(String key, Object value) {
        Object old = get(key);
        set(key, value);
        return old;
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        for (Map.Entry e : m.entrySet()) {
            set((String) e.getKey(), e.getValue());
        }
    }

    @Override
    public Object replace(String key, Object value) {
        Object curValue;
        if (((curValue = get(key)) != null) || containsKey(key)) {
            curValue = put(key, value);
        }
        return curValue;
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
        Object curValue = get(key);
        if (!Objects.equals(curValue, oldValue) ||
                (curValue == null && !containsKey(key))) {
            return false;
        }
        set(key, newValue);
        return true;
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super Object, ?> function) {
        Objects.requireNonNull(function);
        for (Map.Entry<String, Object> entry : entrySet()) {
            String k;
            Object v;
            try {
                k = entry.getKey();
                v = entry.getValue();
            } catch (IllegalStateException ise) {
                // this usually means the entry is no longer in the map.
                throw new ConcurrentModificationException(ise);
            }

            // ise thrown from function is not a cme.
            v = function.apply(k, v);

            try {
                entry.setValue(v);
            } catch (IllegalStateException ise) {
                // this usually means the entry is no longer in the map.
                throw new ConcurrentModificationException(ise);
            }
        }
    }

    @Override
    public Object compute(String key, BiFunction<? super String, ? super Object, ?> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Object oldValue = get(key);

        Object newValue = remappingFunction.apply(key, oldValue);
        if (newValue == null) {
            // delete mapping
            if (oldValue != null || containsKey(key)) {
                // something to remove
                remove(key);
                return null;
            } else {
                // nothing to do. Leave things as they were.
                return null;
            }
        } else {
            // add or replace old mapping
            set(key, newValue);
            return newValue;
        }
    }

    @Override
    public Object computeIfAbsent(String key, Function<? super String, ?> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        Object v;
        if ((v = get(key)) == null) {
            Object newValue;
            if ((newValue = mappingFunction.apply(key)) != null) {
                set(key, newValue);
                return newValue;
            }
        }
        return v;
    }

    @Override
    public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Object oldValue;
        if ((oldValue = get(key)) != null) {
            Object newValue = remappingFunction.apply(key, oldValue);
            if (newValue != null) {
                set(key, newValue);
                return newValue;
            } else {
                remove(key);
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        return new AbstractSet<Map.Entry<String, Object>>() {
            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return new Iterator<Map.Entry<String, Object>>() {
                    private final Iterator<String> iter = new LinkedHashSet<>(BeanMap.super.keySet()).iterator();
                    private Node currentNode;

                    @Override
                    public boolean hasNext() {
                        return iter.hasNext();
                    }

                    @Override
                    public Map.Entry<String, Object> next() {
                        return currentNode = new Node(BeanMap.this, iter.next());
                    }

                    @Override
                    public void remove() {
                        if (currentNode != null) {
                            BeanMap.this.remove(currentNode.getKey());
                        }
                    }
                };
            }

            @Override
            public int size() {
                return BeanMap.super.size();
            }
        };
    }

    @Override
    public Collection<Object> values() {
        return new AbstractCollection<Object>() {
            @Override
            public Iterator<Object> iterator() {
                return new Iterator<Object>() {
                    private final Iterator<String> iter = new LinkedHashSet<>(BeanMap.super.keySet()).iterator();
                    private String currentKey;

                    @Override
                    public boolean hasNext() {
                        return iter.hasNext();
                    }

                    @Override
                    public Object next() {
                        currentKey = iter.next();
                        return BeanMap.this.get(currentKey);
                    }

                    @Override
                    public void remove() {
                        if (currentKey != null) {
                            BeanMap.this.remove(currentKey);
                        }
                    }
                };
            }

            @Override
            public int size() {
                return BeanMap.super.size();
            }
        };
    }

    protected static class Node implements Map.Entry<String, Object> {
        private final BeanMap owner;
        private final String key;

        protected Node(BeanMap owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        @Override
        public Object setValue(Object value) {
            return owner.put(key, value);
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return owner.get(key);
        }
    }

    public static class FieldPropertyDescriptor extends PropertyDescriptor {
        private final Field field;

        public FieldPropertyDescriptor(Field field) throws IntrospectionException {
            super(field.getName(), null, null);
            this.field = field;
        }

        public FieldPropertyDescriptor(Field field, Class<?> beanClass, String readMethodName, String writeMethodName) throws IntrospectionException {
            super(field.getName(), beanClass, readMethodName, writeMethodName);
            this.field = field;
        }

        public Field getField() {
            return field;
        }

        public Annotation[] getDeclaredAnnotations() {
            return field.getDeclaredAnnotations();
        }
    }

    public static class SoftHashMap<K, V> extends AbstractMap<K, V> {
        private final Map<K, SpecialValue> map = new HashMap<>();
        private final ReferenceQueue<? super V> rq = new ReferenceQueue<>();

        @SuppressWarnings("unchecked")
        private void processQueue() {
            SpecialValue sv;
            while ((sv = (SpecialValue) rq.poll()) != null) {
                map.remove(sv.key);
            }
        }

        @Override
        public V get(Object key) {
            SpecialValue ref = map.get(key);
            if (ref == null) {
                map.remove(key);
                return null;
            }
            V value = ref.get();
            if (value == null) {
                map.remove(ref.key);
                return null;
            }
            return value;
        }

        @Override
        public V put(K k, V v) {
            processQueue();
            SpecialValue sv = new SpecialValue(k, v);
            SpecialValue result = map.put(k, sv);
            return (result == null ? null : result.get());
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            if (map.isEmpty()) {
                return Collections.<K, V>emptyMap().entrySet();
            }
            Map<K, V> currentContents = new HashMap<K, V>();
            for (Entry<K, SpecialValue> entry : map.entrySet()) {
                V currentValueForEntry = entry.getValue().get();
                if (currentValueForEntry != null) {
                    currentContents.put(entry.getKey(), currentValueForEntry);
                }
            }
            return currentContents.entrySet();
        }

        @Override
        public void clear() {
            processQueue();
            map.clear();
        }

        @Override
        public int size() {
            processQueue();
            return map.size();
        }

        @Override
        public V remove(Object k) {
            processQueue();
            SpecialValue ref = map.remove(k);
            if (ref == null) {
                return null;
            }
            return ref.get();
        }

        class SpecialValue extends SoftReference<V> {
            private final K key;

            SpecialValue(K k, V v) {
                super(v, rq);
                this.key = k;
            }
        }
    }


}
