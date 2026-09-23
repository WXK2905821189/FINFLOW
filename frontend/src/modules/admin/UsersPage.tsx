import { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Pagination,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  message,
  type TableColumnsType,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { rbacApi, userApi } from '../../services/api';
import { useRemote, ResourceFailure, StatusTag } from '../shared/components';
import type { PageResponse, User } from '../../types';
import type { PermissionOverrideItem, SysPermission, SysRole } from './types';
import { BUILT_IN_ROLE_CODES, PROTECTED_ROLE_CODES, ROLE_LABELS, USER_STATUS_OPTIONS } from './types';

/** V45：账号权限行操作标签。 */
const OVERRIDE_EFFECT_LABELS: Record<PermissionOverrideItem['effect'], string> = {
  GRANT: '授予',
  DENY: '剔除',
};

const DOMAIN_LABELS: Record<string, string> = {
  dashboard: '工作台',
  user: '用户',
  role: '角色',
  bank: '银行账户',
  statement: '标准流水',
  voucher: '金蝶制证',
  reconciliation: '对账',
  connection: '连接',
  operation: '采集运营',
  data: '能力状态',
  bankdata: '银行数据',
  feishu: '飞书协同',
  validation: '校验规则',
  closing: '结账',
  audit: '审计',
  system: '系统',
  ai: 'AI 能力',
};

/** 密码安全口径（模块文档 §5.3）：任何角色都看不到明文密码，管理员只能重置。 */
const PASSWORD_NOTICE = '系统以单向加密存储密码，任何角色（含超级管理员）均无法查看明文密码。新增账号或重置密码后，请通过安全渠道线下告知本人。';

function roleLabel(code: string) {
  return ROLE_LABELS[code] || code;
}

function AccountsTab() {
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<User | null>(null);
  const [creating, setCreating] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  // V45：权限覆盖抽屉状态（overrideUser 非空即打开）
  const [overrideUser, setOverrideUser] = useState<User | null>(null);
  const [overrides, setOverrides] = useState<PermissionOverrideItem[]>([]);
  const [overrideLoading, setOverrideLoading] = useState(false);
  const [overrideSaving, setOverrideSaving] = useState(false);

  const rolesLoader = useCallback(() => rbacApi.roles(), []);
  const { data: roles } = useRemote<SysRole[]>(rolesLoader, [rolesLoader]);
  const usersLoader = useCallback(() => userApi.list({ page, size: 20 }), [page]);
  const { data, loading, error, reload } = useRemote<PageResponse<User>>(usersLoader, [usersLoader]);

  const roleIdByCode = useMemo(() => new Map((roles || []).map((role) => [role.code, role.id])), [roles]);

  // V45：权限目录（供覆盖编辑下拉选择，code 从服务端取，绝不前端杜撰）
  const permissionsLoader = useCallback(() => rbacApi.permissions(), []);
  const { data: permissions } = useRemote<SysPermission[]>(permissionsLoader, [permissionsLoader]);

  /** V45：打开覆盖抽屉，加载当前覆盖 + 角色默认权限（只读展示）。 */
  const openOverrides = async (user: User) => {
    setOverrideUser(user);
    setOverrideLoading(true);
    try {
      const response = await userApi.permissionOverrides(user.id);
      setOverrides(response.overrides);
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '加载权限覆盖失败');
      setOverrideUser(null);
    } finally {
      setOverrideLoading(false);
    }
  };

  /** V45：保存前本地校验（同 code GRANT+DENY 后端会 400，前端先拦一道给更快反馈）。 */
  const submitOverrides = async () => {
    if (!overrideUser) return;
    const seen = new Map<string, PermissionOverrideItem['effect']>();
    for (const item of overrides) {
      const previous = seen.get(item.code);
      if (previous && previous !== item.effect) {
        message.error(`同一权限 ${item.code} 不能同时「授予」和「剔除」`);
        return;
      }
      seen.set(item.code, item.effect);
    }
    setOverrideSaving(true);
    try {
      const response = await userApi.replacePermissionOverrides(overrideUser.id, overrides);
      setOverrides(response.overrides);
      message.success('权限覆盖已保存，该账号下一个请求即按新有效权限鉴权');
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '保存失败');
    } finally {
      setOverrideSaving(false);
    }
  };

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE' });
    setCreating(true);
  };

  const openEdit = (user: User) => {
    form.resetFields();
    form.setFieldsValue({
      username: user.username,
      email: user.email,
      phone: user.phone,
      status: user.status,
      roleIds: user.roles.map((code) => roleIdByCode.get(code)).filter((id): id is number => id != null),
    });
    setEditing(user);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      if (editing) {
        await userApi.update(editing.id, { ...values, password: values.password || undefined });
        message.success('账号已更新');
        setEditing(null);
      } else {
        await userApi.create({ ...values, password: values.password || undefined });
        message.success('账号已创建，请线下告知初始密码');
        setCreating(false);
      }
      reload();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '保存失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const toggleStatus = async (user: User) => {
    const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      await userApi.update(user.id, {
        username: user.username,
        email: user.email,
        phone: user.phone,
        status: nextStatus,
        roleIds: user.roles.map((code) => roleIdByCode.get(code)).filter((id): id is number => id != null),
      });
      message.success(nextStatus === 'ACTIVE' ? '账号已启用' : '账号已停用，其登录会话将全部失效');
      reload();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '操作失败');
    }
  };

  /** V36-W5（需求5）：物理删除。有业务/审计引用的账号后端会 409，文案引导改用停用。 */
  const confirmRemove = (user: User) => {
    Modal.confirm({
      title: `删除账号「${user.username}」？`,
      content: '物理删除后不可恢复，该账号的登录会话立即失效。有制证、审计、批次等业务记录的账号无法删除（会提示改用停用）；删除仅适用于清理从未真正使用的误建账号。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await userApi.remove(user.id);
          message.success('账号已删除');
          reload();
        } catch (requestError) {
          message.error(requestError instanceof Error ? requestError.message : '删除失败');
        }
      },
    });
  };

  const columns: TableColumnsType<User> = [
    { title: '用户名', dataIndex: 'username', width: 160 },
    { title: '邮箱', dataIndex: 'email', width: 220 },
    { title: '手机号', dataIndex: 'phone', width: 130, render: (value) => value || '--' },
    {
      title: '角色',
      dataIndex: 'roles',
      render: (roleCodes: string[]) => (
        <Space size={4} wrap>
          {roleCodes.length === 0 ? <span className="muted">未分配</span> : roleCodes.map((code) => (
            <Tag key={code} color={code === 'ADMIN' ? 'gold' : 'blue'}>{roleLabel(code)}</Tag>
          ))}
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (status) => <StatusTag status={status} /> },
    {
      title: '操作',
      key: 'actions',
      width: 205,
      render: (_, record) => (
        <Space size={8}>
          <Button size="small" onClick={() => openEdit(record)}>编辑 / 重置密码</Button>
          <Button size="small" onClick={() => void openOverrides(record)}>权限覆盖</Button>
          <Popconfirm
            title={record.status === 'ACTIVE' ? '停用该账号？' : '启用该账号？'}
            description={record.status === 'ACTIVE' ? '停用后该账号立即无法访问系统。' : undefined}
            onConfirm={() => toggleStatus(record)}
          >
            <Button size="small" danger={record.status === 'ACTIVE'}>
              {record.status === 'ACTIVE' ? '停用' : '启用'}
            </Button>
          </Popconfirm>
          <Tooltip title="物理删除，不可恢复。仅适用于从未真正使用的误建账号；有业务或审计记录的账号会被拒绝，请改用停用。">
            <Button size="small" danger onClick={() => confirmRemove(record)}>删除</Button>
          </Tooltip>
        </Space>
      ),
    },
  ];

  const isCreate = !editing;

  return (
    <>
      <Card>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Alert type="info" showIcon message={PASSWORD_NOTICE} style={{ flex: 1, marginRight: 16 }} />
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增账号</Button>
          </Space>
        </Space>
      </Card>
      <Card>
        {error ? <ResourceFailure error={error} onRetry={reload} /> : (
          <>
            <Table rowKey="id" loading={loading} columns={columns} dataSource={data?.records || []}
              pagination={false} locale={{ emptyText: <Empty description="还没有账号" /> }} scroll={{ x: 900 }} />
            {data && data.total > data.size && (
              <Pagination className="table-pagination" current={data.page} pageSize={data.size}
                total={data.total} onChange={setPage} />
            )}
          </>
        )}
      </Card>

      {/* forceRender：openCreate/openEdit 先调 form.resetFields/setFieldsValue 再打开弹窗，
          不预渲染 Form 会与 rc-field-form 的挂载校验抢时序，间歇打印 "useForm is not connected"。 */}
      <Modal
        title={isCreate ? '新增账号' : `编辑账号：${editing?.username}`}
        open={creating || editing != null}
        onCancel={() => { setCreating(false); setEditing(null); }}
        onOk={submit}
        confirmLoading={confirmLoading}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
        forceRender
      >
        <Alert type="info" showIcon message={PASSWORD_NOTICE} style={{ marginBottom: 16 }} />
        <Form form={form} layout="vertical">
          {/* W8：用户名/邮箱放开编辑（后端 updateUser 本就全量更新并做唯一性校验；
              user:manage 权限者均可改，改用户名不影响在线会话，下次登录用新名）。
              W9：min=3 拿掉——中文姓名两个字符是正常场景，后端同口径（NotBlank + max 64）。 */}
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, whitespace: true, message: '请输入用户名' },
              { max: 64, message: '用户名最多 64 个字符' },
            ]}
          >
            <Input placeholder="登录用户名（支持中文姓名，如：张三）" />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: true, type: 'email', message: '邮箱格式不正确' }]}>
            <Input placeholder="name@company.com" />
          </Form.Item>
          <Form.Item name="phone" label="手机号"><Input placeholder="选填" /></Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={USER_STATUS_OPTIONS} />
          </Form.Item>
          <Form.Item name="roleIds" label="角色（至少一个）" rules={[{ required: true, message: '至少选择一个角色' }]}>
            <Select mode="multiple" placeholder="选择角色"
              options={(roles || []).map((role) => ({ value: role.id, label: `${role.name}（${role.code}）` }))} />
          </Form.Item>
          <Form.Item
            name="password"
            label={isCreate ? '初始密码' : '重置密码（留空表示不修改）'}
            rules={[
              { required: isCreate, message: '请输入初始密码' },
              { min: 8, message: '密码至少 8 位' },
              { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '密码必须同时包含字母和数字' },
            ]}
            extra={isCreate ? '创建成功后系统不会再次显示该密码。' : '重置后该用户所有已登录会话立即失效。'}
          >
            <Input.Password autoComplete="new-password" placeholder="至少 8 位，须含字母和数字" />
          </Form.Item>
        </Form>
      </Modal>

      {/* V45：账号级权限覆盖抽屉 —— 角色默认权限（只读）+ 覆盖列表（GRANT/DENY 可增删），保存走 PUT 全量替换。 */}
      <Drawer
        title={`权限覆盖：${overrideUser?.username ?? ''}`}
        width={640}
        open={overrideUser != null}
        onClose={() => setOverrideUser(null)}
        extra={
          <Space>
            <Button onClick={() => setOverrideUser(null)}>取消</Button>
            <Button type="primary" loading={overrideSaving} onClick={submitOverrides}>保存</Button>
          </Space>
        }
      >
        {overrideUser && (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="生效公式"
              description="有效权限 = (角色权限 ∪ 账号「授予」) − 账号「剔除」。覆盖只影响该账号，保存后下一个请求即生效；不允许对超管账号或自己剔除 role:manage。"
            />
            <Card size="small" title="角色默认权限（只读）" style={{ marginBottom: 16 }}>
              {(() => {
                const roleIds = overrideUser.roles
                  .map((code) => roleIdByCode.get(code))
                  .filter((id): id is number => id != null);
                const rolePermissionIds = new Set(
                  (roles || []).filter((role) => roleIds.includes(role.id))
                    .flatMap((role) => role.permissionIds || []),
                );
                const defaults = (permissions || []).filter((permission) => rolePermissionIds.has(permission.id));
                return defaults.length === 0 ? (
                  <span className="muted">（角色未携带权限）</span>
                ) : (
                  <Space size={4} wrap>
                    {defaults.map((permission) => (
                      <Tooltip key={permission.code} title={permission.name}>
                        <Tag>{permission.code}</Tag>
                      </Tooltip>
                    ))}
                  </Space>
                );
              })()}
            </Card>
            <Card
              size="small"
              title="账号覆盖"
              extra={(
                <Button
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={() => {
                    const firstPermission = (permissions || [])[0];
                    if (!firstPermission) {
                      message.warning('权限目录为空');
                      return;
                    }
                    setOverrides([...overrides, { code: firstPermission.code, effect: 'GRANT' }]);
                  }}
                >
                  添加覆盖
                </Button>
              )}
              loading={overrideLoading}
            >
              {overrides.length === 0 ? (
                <Empty description="没有覆盖，账号完全按角色权限生效" />
              ) : (
                <Space direction="vertical" style={{ width: '100%' }} size={8}>
                  {overrides.map((item, index) => (
                    <Space key={`${item.code}-${index}`} style={{ display: 'flex' }} wrap>
                      <Select
                        style={{ width: 280 }}
                        value={item.code}
                        options={(permissions || []).map((permission) => ({
                          value: permission.code,
                          label: `${permission.code}（${permission.name}）`,
                        }))}
                        onChange={(code) => {
                          const next = [...overrides];
                          next[index] = { ...item, code };
                          setOverrides(next);
                        }}
                      />
                      <Select
                        style={{ width: 110 }}
                        value={item.effect}
                        options={(Object.keys(OVERRIDE_EFFECT_LABELS) as PermissionOverrideItem['effect'][])
                          .map((effect) => ({ value: effect, label: OVERRIDE_EFFECT_LABELS[effect] }))}
                        onChange={(effect) => {
                          const next = [...overrides];
                          // Select onChange 参数是宽 string，收窄到 GRANT/DENY 字面量
                          next[index] = { ...item, effect: effect as PermissionOverrideItem['effect'] };
                          setOverrides(next);
                        }}
                      />
                      <Button
                        size="small"
                        danger
                        onClick={() => setOverrides(overrides.filter((_, removeIndex) => removeIndex !== index))}
                      >
                        移除
                      </Button>
                    </Space>
                  ))}
                </Space>
              )}
            </Card>
          </>
        )}
      </Drawer>
    </>
  );
}

function RolesTab() {
  const [form] = Form.useForm();
  const [createOpen, setCreateOpen] = useState(false);
  const [editingRole, setEditingRole] = useState<SysRole | null>(null);
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<number[]>([]);
  const [confirmLoading, setConfirmLoading] = useState(false);

  const loader = useCallback(async () => {
    const [roleList, permissionList] = await Promise.all([rbacApi.roles(), rbacApi.permissions()]);
    return { roles: roleList, permissions: permissionList };
  }, []);
  const { data, loading, error, reload } = useRemote<{ roles: SysRole[]; permissions: SysPermission[] }>(loader, [loader]);

  const permissionGroups = useMemo(() => {
    const groups = new Map<string, SysPermission[]>();
    for (const permission of data?.permissions || []) {
      const domain = permission.code.split(':')[0];
      if (!groups.has(domain)) groups.set(domain, []);
      groups.get(domain)!.push(permission);
    }
    return [...groups.entries()];
  }, [data]);

  const openCreate = () => { form.resetFields(); setCreateOpen(true); };

  const openEdit = (role: SysRole) => {
    form.resetFields();
    form.setFieldsValue({ name: role.name, description: role.description });
    setSelectedPermissionIds(role.permissionIds || []);
    setEditingRole(role);
  };

  const submitCreate = async () => {
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      await rbacApi.createRole({ ...values, code: values.code.toUpperCase(), permissionIds: values.permissionIds || [] });
      message.success('角色已创建');
      setCreateOpen(false);
      reload();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '创建失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const submitEdit = async () => {
    if (!editingRole) return;
    const values = await form.validateFields();
    setConfirmLoading(true);
    try {
      await rbacApi.updateRole(editingRole.id, {
        name: values.name,
        description: values.description,
        permissionIds: selectedPermissionIds,
      });
      message.success('角色权限已更新，持有该角色的用户下次请求即生效');
      setEditingRole(null);
      reload();
    } catch (requestError) {
      message.error(requestError instanceof Error ? requestError.message : '更新失败');
    } finally {
      setConfirmLoading(false);
    }
  };

  const columns: TableColumnsType<SysRole> = [
    { title: '角色编码', dataIndex: 'code', width: 180, render: (code) => <span className="mono">{code}</span> },
    { title: '名称', dataIndex: 'name', width: 160, render: (name, record) => {
      const label = roleLabel(record.code);
      return label === name ? name : `${label} / ${name}`;
    } },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '成员数',
      dataIndex: 'userCount',
      width: 80,
      render: (value?: number) => (value == null ? '--' : `${value} 人`),
    },
    {
      title: '类型',
      key: 'type',
      width: 110,
      render: (_, record) => PROTECTED_ROLE_CODES.includes(record.code)
        ? <Tag color="purple">系统保护</Tag>
        : BUILT_IN_ROLE_CODES.includes(record.code)
          ? <Tag color="geekblue">内置可调</Tag>
          : <Tag>自定义</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_, record) => PROTECTED_ROLE_CODES.includes(record.code) ? (
        <Tooltip title="ADMIN 是安全锚点（超管自身的 role:manage/user:manage），不可修改；其余内置与自定义角色均可在本页可视化调整权限。">
          <Button size="small" disabled>编辑权限</Button>
        </Tooltip>
      ) : (
        <Button size="small" onClick={() => openEdit(record)}>编辑权限</Button>
      ),
    },
  ];

  return (
    <>
      <Card>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <span className="muted">权限编码基线见 docs/permission-catalog.md（共 {data?.permissions?.length ?? '…'} 项）；V33 起内置角色（除 ADMIN）与自定义角色均可在此可视化调整，权限变更即时生效并写入审计。</span>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建角色</Button>
        </Space>
      </Card>
      <Card>
        {error ? <ResourceFailure error={error} onRetry={reload} /> : (
          <Table rowKey="id" loading={loading} columns={columns} dataSource={data?.roles || []}
            pagination={false} locale={{ emptyText: <Empty description="没有角色" /> }} />
        )}
      </Card>

      {/* forceRender：openCreate 先调 form.resetFields 再打开弹窗，理由同账号弹窗。 */}
      <Modal title="新建角色" open={createOpen} onCancel={() => setCreateOpen(false)}
        onOk={submitCreate} confirmLoading={confirmLoading} okText="创建" cancelText="取消" destroyOnHidden forceRender>
        <Form form={form} layout="vertical">
          <Form.Item name="code" label="角色编码" rules={[
            { required: true, message: '请输入角色编码' },
            { pattern: /^[A-Z0-9_]{2,64}$/, message: '仅限大写字母、数字与下划线（2-64 位）' },
          ]}>
            <Input placeholder="例如 PAYROLL_OPERATOR" />
          </Form.Item>
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="例如 工资代发操作员" />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input placeholder="选填" /></Form.Item>
          <Form.Item name="permissionIds" label="权限（至少一个）" rules={[{ required: true, message: '至少选择一项权限' }]}>
            <Checkbox.Group style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', rowGap: 4 }}>
              {(data?.permissions || []).map((permission) => (
                <Checkbox key={permission.id} value={permission.id}>{permission.code}</Checkbox>
              ))}
            </Checkbox.Group>
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`编辑角色权限：${editingRole ? roleLabel(editingRole.code) : ''}`}
        width={560}
        open={editingRole != null}
        onClose={() => setEditingRole(null)}
        extra={
          <Space>
            <Button onClick={() => setEditingRole(null)}>取消</Button>
            <Button type="primary" loading={confirmLoading} onClick={submitEdit}>保存</Button>
          </Space>
        }
      >
        {editingRole && (
          <>
            <Form form={form} layout="vertical">
              <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
                <Input />
              </Form.Item>
              <Form.Item name="description" label="描述"><Input /></Form.Item>
            </Form>
            <Alert type="info" showIcon style={{ marginBottom: 16 }}
              message="权限变更立即生效" description="持有该角色的用户在下一个请求即按新权限集鉴权，无需重新登录。" />
            {permissionGroups.map(([domain, permissions]) => {
              const groupIds = permissions.map((permission) => permission.id);
              const allChecked = groupIds.every((id) => selectedPermissionIds.includes(id));
              const someChecked = groupIds.some((id) => selectedPermissionIds.includes(id));
              return (
                <Card key={domain} size="small" title={
                  <Checkbox
                    checked={allChecked}
                    indeterminate={!allChecked && someChecked}
                    onChange={(event) => {
                      const next = new Set(selectedPermissionIds);
                      groupIds.forEach((id) => event.target.checked ? next.add(id) : next.delete(id));
                      setSelectedPermissionIds([...next]);
                    }}
                  >
                    {DOMAIN_LABELS[domain] || domain}
                  </Checkbox>
                } style={{ marginBottom: 8 }}>
                  <Checkbox.Group
                    style={{ display: 'grid', gridTemplateColumns: '1fr', rowGap: 4 }}
                    value={selectedPermissionIds.filter((id) => groupIds.includes(id))}
                    onChange={(values) => {
                      const next = new Set(selectedPermissionIds);
                      groupIds.forEach((id) => next.delete(id));
                      values.forEach((id) => next.add(id as number));
                      setSelectedPermissionIds([...next]);
                    }}
                  >
                    {permissions.map((permission) => (
                      <Checkbox key={permission.id} value={permission.id}>
                        <span className="mono">{permission.code}</span>
                        <span className="muted" style={{ marginLeft: 8 }}>{permission.name}</span>
                      </Checkbox>
                    ))}
                  </Checkbox.Group>
                </Card>
              );
            })}
          </>
        )}
      </Drawer>
    </>
  );
}

/** /users 页面（user:manage 门控，仅超级管理员可见，模块文档 §5）。 */
export function UserAdminPage() {
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="section-kicker">系统管理</span>
          <h2>用户与角色</h2>
          <p className="muted">仅超级管理员可见。账号与角色变更会记录到审计中心；密码仅可重置、不可查看。</p>
        </div>
      </div>
      <Tabs
        defaultActiveKey="accounts"
        items={[
          { key: 'accounts', label: '账号管理', children: <AccountsTab /> },
          { key: 'roles', label: '角色与权限', children: <RolesTab /> },
        ]}
      />
    </>
  );
}
