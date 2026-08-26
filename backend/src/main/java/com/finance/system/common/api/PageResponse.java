package com.finance.system.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

public record PageResponse<T>(long page, long size, long total, List<T> records) {

    public static <T> PageResponse<T> from(IPage<T> source) {
        return new PageResponse<>(source.getCurrent(), source.getSize(), source.getTotal(), source.getRecords());
    }
}
