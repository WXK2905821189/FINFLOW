package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.BankSyncSchedule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BankSyncScheduleMapper extends BaseMapper<BankSyncSchedule> {
}
