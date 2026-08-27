package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.BankDataSyncTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BankDataSyncTaskMapper extends BaseMapper<BankDataSyncTask> {
}
