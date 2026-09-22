package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.BankPushJob;
import org.apache.ibatis.annotations.Mapper;

/** 一键推送至金蝶异步任务（W16-A1）持久层。 */
@Mapper
public interface BankPushJobMapper extends BaseMapper<BankPushJob> {
}
