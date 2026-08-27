package com.finance.system.bankdata.scope;

import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyScopeServiceTest {

    @Test
    void usesPersistedCompanyAndNeverAcceptsClientScope() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setCompanyId(42L);
        when(mapper.selectById(7L)).thenReturn(user);

        CompanyScopeService service = new CompanyScopeService(mapper);

        assertEquals(42L, service.companyIdForUser(7L));
    }

    @Test
    void fallsBackOnlyForLegacyDevelopmentUsers() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        SysUser legacyUser = new SysUser();
        legacyUser.setId(8L);
        when(mapper.selectById(8L)).thenReturn(legacyUser);

        CompanyScopeService service = new CompanyScopeService(mapper);

        assertEquals(CompanyScopeService.DEFAULT_DEVELOPMENT_COMPANY_ID, service.companyIdForUser(8L));
    }
}
