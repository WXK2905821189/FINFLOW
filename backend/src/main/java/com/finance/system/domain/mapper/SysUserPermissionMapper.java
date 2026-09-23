package com.finance.system.domain.mapper;

import com.finance.system.domain.entity.SysUserPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserPermissionMapper {

    @Select("SELECT id, user_id, permission_code, effect, created_by, created_at FROM sys_user_permission WHERE user_id = #{userId}")
    List<SysUserPermission> findByUserId(Long userId);

    @Insert("INSERT INTO sys_user_permission (user_id, permission_code, effect, created_by) "
            + "VALUES (#{userId}, #{permissionCode}, #{effect}, #{createdBy})")
    int insert(SysUserPermission override);

    @Delete("DELETE FROM sys_user_permission WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);
}
