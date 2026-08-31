package com.finance.system.bankdata.scope;

import com.finance.system.domain.mapper.SysUserMapper;

/** @deprecated Use the shared tenant service outside the bankdata domain. */
@Deprecated
public class CompanyScopeService extends com.finance.system.common.tenant.CompanyScopeService {

    public CompanyScopeService(SysUserMapper userMapper) {
        super(userMapper);
    }
}
