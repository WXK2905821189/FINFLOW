package com.finance.system.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.system.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
