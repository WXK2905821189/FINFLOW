package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.StatementRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StatementRecordMapper extends BaseMapper<StatementRecord> {
}
