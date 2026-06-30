package com.hify.common.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

@Getter
public class PageResult<T> {

    private final List<T> list;
    private final long total;
    private final int page;
    private final int pageSize;

    @JsonCreator
    private PageResult(@JsonProperty("list") List<T> list,
                       @JsonProperty("total") long total,
                       @JsonProperty("page") int page,
                       @JsonProperty("pageSize") int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> Result<PageResult<T>> of(List<T> list, long total, int page, int pageSize) {
        return Result.ok(new PageResult<>(list, total, page, pageSize));
    }
}
