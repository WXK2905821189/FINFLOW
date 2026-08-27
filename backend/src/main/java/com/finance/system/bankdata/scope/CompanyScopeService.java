package com.finance.system.bankdata.scope;

import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class CompanyScopeService {

    public static final long DEFAULT_DEVELOPMENT_COMPANY_ID = 1L;

    private final SysUserMapper userMapper;

    public CompanyScopeService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public long companyIdForUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "Authentication is required");
        }
        return user.getCompanyId() == null ? DEFAULT_DEVELOPMENT_COMPANY_ID : user.getCompanyId();
    }
}
