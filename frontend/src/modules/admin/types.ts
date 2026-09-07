/**
 * 用户与角色模块（/users，user:manage 门控）的前端类型。
 * 与后端 SysRole / SysPermission 实体、UserUpsertRequest 校验规则一一对应。
 */
export type SysRole = {
  id: number;
  code: string;
  name: string;
  description?: string;
  /** 权限 id 集合（GET /api/rbac/roles 返回），用于角色编辑器初始化勾选。 */
  permissionIds?: number[];
};

export type SysPermission = {
  id: number;
  code: string;
  name: string;
  description?: string;
};

export type RoleCreatePayload = {
  code: string;
  name: string;
  description?: string;
  permissionIds: number[];
};

export type RoleUpdatePayload = {
  name: string;
  description?: string;
  permissionIds: number[];
};

/** Mirrors backend UserUpsertRequest: password blank on update means "keep current". */
export type UserUpsertPayload = {
  username: string;
  email: string;
  phone?: string;
  status: string;
  roleIds: number[];
  password?: string;
};

/** 内置角色（V1 种子）只读，权限与名称不可经 API 修改（后端 RoleController 同步拦截）。 */
export const BUILT_IN_ROLE_CODES = ['ADMIN', 'FINANCE_STAFF', 'FINANCE_MANAGER', 'VIEWER'];

export const USER_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '正常' },
  { value: 'PENDING', label: '待激活' },
  { value: 'DISABLED', label: '停用' },
];

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: '系统管理员',
  FINANCE_STAFF: '财务专员',
  FINANCE_MANAGER: '财务经理',
  VIEWER: '业务查看者',
};
