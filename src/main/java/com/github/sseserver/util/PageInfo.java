package com.github.sseserver.util;

import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PageInfo<T> implements Serializable, Iterable<T> {
    private static final long serialVersionUID = 1L;

    private long total;
    private int pageNum;
    private int pageSize;
    private List<T> list;

    public PageInfo() {
        this(new ArrayList<>());
    }

    public PageInfo(List<T> list) {
        this.list = Objects.requireNonNull(list);
        this.total = (long) list.size();
    }

    public static <T> PageInfo<T> empty() {
        return PageInfo.of(Collections.emptyList());
    }

    public static <T> PageInfo<T> of(List<T> list, int pageNum, int pageSize) {
        return of(list, pageNum, pageSize, false);
    }

    /**
     * 分页
     *
     * @param list
     * @param pageNum
     * @param pageSize
     * @param copy     是否复制. true=返回复制的集合. false=不复制(引用原始对象)
     * @param <T>
     * @return 分好页的数据
     */
    public static <T> PageInfo<T> of(List<T> list, int pageNum, int pageSize, boolean copy) {
        int total = list.size();
        List<T> sublist;
        if (pageSize <= 0) {
            sublist = Collections.emptyList();
        } else {
            int offsetBegin = (Math.max(pageNum, 1) - 1) * pageSize;
            int offsetEnd = Math.min(offsetBegin + pageSize, total);
            if (offsetBegin == 1 && offsetEnd == total) {
                sublist = list;
            } else {
                sublist = list.subList(Math.min(offsetBegin, total), offsetEnd);
                if (sublist.isEmpty()) {
                    sublist = Collections.emptyList();
                }
            }
            if (copy) {
                sublist = new ArrayList<>(sublist);
            }
        }

        PageInfo<T> pageInfo = new PageInfo<>();
        pageInfo.setList(sublist);
        pageInfo.setTotal(total);
        pageInfo.setPageNum(pageNum);
        pageInfo.setPageSize(pageSize);
        return pageInfo;
    }

    public static <SOURCE, TARGET> PageInfo<TARGET> of(List<SOURCE> source, Function<SOURCE, TARGET> map) {
        PageInfo target = new PageInfo<>(source);
        target.setList(source.stream()
                .map(map)
                .collect(Collectors.toList()));
        return target;
    }

    public static <T> PageInfo<T> of(List<T> list) {
        return new PageInfo<>(list);
    }

    public static <SOURCE, TARGET> PageInfo<TARGET> of(PageInfo<SOURCE> source, Function<SOURCE, TARGET> map) {
        PageInfo<TARGET> target = new PageInfo<>();
        target.pageNum = source.pageNum;
        target.pageSize = source.pageSize;
        target.total = source.total;
        target.setList(source.list.stream().map(map).collect(Collectors.toList()));
        return target;
    }

    public <TARGET> PageInfo<TARGET> map(Function<T, TARGET> map) {
        return of(this, map);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    @Override
    public Iterator<T> iterator() {
        if (list == null) {
            return Collections.emptyIterator();
        } else {
            return list.iterator();
        }
    }

    public Stream<T> stream() {
        return list == null ? Stream.empty() : list.stream();
    }
}
