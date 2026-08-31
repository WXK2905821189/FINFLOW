package com.finance.system.domain.mapper;

import com.finance.system.domain.entity.SysRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysRolePermissionMapper {

    @Select({
            "<script>",
            "SELECT role_id, permission_id FROM sys_role_permission",
            "WHERE role_id IN",
            "<foreach collection='roleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach>",
            "</script>"
    })
    List<SysRolePermission> findByRoleIds(@Param("roleIds") Collection<Long> roleIds);

    @Insert("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    int insert(SysRolePermission relation);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(Long roleId);
}
