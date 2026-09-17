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
  /** 持有该角色的账号数（V33：编辑角色权限前先看影响面）。 */
  userCount?: number;
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

/** 内置角色（V1 种子）。V33 起 FINANCE_STAFF / FINANCE_MANAGER / VIEWER 的权限集
 *  可由超管在角色编辑器可视化调整（保存即生效、写入审计）；仅 ADMIN 受保护不可改。 */
export const BUILT_IN_ROLE_CODES = ['ADMIN', 'FINANCE_STAFF', 'FINANCE_MANAGER', 'VIEWER'];

/** 受保护角色：安全锚点（持 role:manage/user:manage），后端 RbacService.PROTECTED_ROLE_CODES 同步拦截。 */
export const PROTECTED_ROLE_CODES = ['ADMIN'];

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
