package com.finance.system.domain.mapper;

import com.finance.system.domain.entity.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper {

    @Select("SELECT user_id, role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<SysUserRole> findByUserId(Long userId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insert(SysUserRole relation);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);
}
