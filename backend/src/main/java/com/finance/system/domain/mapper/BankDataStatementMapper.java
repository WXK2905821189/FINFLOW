package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.BankDataStatement;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BankDataStatementMapper extends BaseMapper<BankDataStatement> {
}
