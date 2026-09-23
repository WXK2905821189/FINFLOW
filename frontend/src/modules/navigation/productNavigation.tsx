import {
  ApartmentOutlined,
  AuditOutlined,
  CalendarOutlined,
  CodeOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  ExceptionOutlined,
  FileTextOutlined,
  NotificationOutlined,
  ProfileOutlined,
  RadarChartOutlined,
  RobotOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  TeamOutlined,
  TransactionOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Link } from 'react-router-dom';

export const PRODUCT_MENU_STORAGE_KEY = 'finflow.product-menu-open';

export const pageTitles: Record<string, string> = {
  '/dashboard': '财务工作台',
  '/bank-access/accounts': '银行账户',
  '/bank-access/tasks': '同步任务',
  '/bank-access/logs': '运行日志',
  '/bank-access/data/balances': '余额查询',
  '/bank-access/data/statements': '流水查询',
  '/bank-access/raw-messages': '原始报文',
  '/statements/vouchers': '凭证中心',
  '/statements/voucher-problems': '问题凭证',
  '/voucher-rules': '规则中心',
  '/closing': '账期结账',
  '/users': '用户管理',
  '/system/dicts': '字典中心',
  '/system/ai': 'AI 状态',
  '/system/ai-settings': 'AI 设置',
  '/audit': '审计中心',
  '/feishu': '飞书配置',
};

type HasPermission = (permission: string) => boolean;

/**
 * 产品导航（V34 ⑥⑦② 页面增删后的三组结构，2026-09-17）：
 *  - 「银行数据」：账户 + 查询（余额/流水）与采集运营（任务/日志/报文）；对账核对已并入工作台（V34 ⑥）；
 *  - 「凭证与入账」：规则中心（V34 ② + W4 合并科目与往来规则，CRUD/分组/Excel 导入）+ 凭证中心（V34 ⑦）；
 *  - 「系统管理」：低频页收纳（账期结账/飞书配置等）；三方对账页下线（并入工作台）。
 */
export function buildProductNavigation(hasPermission: HasPermission): MenuProps['items'] {
  const canViewFeishu = hasPermission('feishu:view') || hasPermission('feishu:manage');
  const canViewTasks = hasPermission('operation:monitor') || hasPermission('bankdata:view');
  const canViewBankData = [
    'bankdata:balance:view', 'bankdata:statement:view',
  ].some(hasPermission);
  const canViewRawMessages = hasPermission('bankdata:raw:view');
  const canViewClosing = hasPermission('closing:view') || hasPermission('closing:manage');
  const canViewVoucher = hasPermission('voucher:push');

  const bankDataChildren = [
    ...(canViewBankData ? [{ key: '/bank-access/data/balances', icon: <WalletOutlined />, label: <Link to="/bank-access/data/balances">余额查询</Link> }] : []),
    ...(canViewBankData ? [{ key: '/bank-access/data/statements', icon: <TransactionOutlined />, label: <Link to="/bank-access/data/statements">流水查询</Link> }] : []),
    ...(canViewTasks ? [{ key: '/bank-access/tasks', icon: <RadarChartOutlined />, label: <Link to="/bank-access/tasks">同步任务</Link> }] : []),
    ...(hasPermission('operation:log:view') ? [{ key: '/bank-access/logs', icon: <FileTextOutlined />, label: <Link to="/bank-access/logs">运行日志</Link> }] : []),
    // Standalone entry: this is the only surface that shows a bank response in full,
    // so it is deliberately not nested under 数据查询 (which only shows projections).
    ...(canViewRawMessages ? [{ key: '/bank-access/raw-messages', icon: <CodeOutlined />, label: <Link to="/bank-access/raw-messages">原始报文</Link> }] : []),
  ];

  // V34 ②⑦ + W4 + W16-A2：凭证链路一等页面组——规则中心 + 凭证中心 + 问题凭证（落桶修复闭环）。
  const voucherChildren = [
    ...(canViewVoucher ? [{ key: '/voucher-rules', icon: <ProfileOutlined />, label: <Link to="/voucher-rules">规则中心</Link> }] : []),
    ...(canViewVoucher ? [{ key: '/statements/vouchers', icon: <SendOutlined />, label: <Link to="/statements/vouchers">凭证中心</Link> }] : []),
    ...(canViewVoucher ? [{ key: '/statements/voucher-problems', icon: <ExceptionOutlined />, label: <Link to="/statements/voucher-problems">问题凭证</Link> }] : []),
  ];

  const systemChildren = [
    ...(hasPermission('user:manage') ? [{ key: '/users', icon: <TeamOutlined />, label: <Link to="/users">用户与角色</Link> }] : []),
    ...(hasPermission('audit:view') ? [{ key: '/audit', icon: <AuditOutlined />, label: <Link to="/audit">审计中心</Link> }] : []),
    ...(hasPermission('system:dict:manage') ? [{ key: '/system/dicts', icon: <DatabaseOutlined />, label: <Link to="/system/dicts">字典中心</Link> }] : []),
    ...(hasPermission('ai:use') ? [{ key: '/system/ai', icon: <RobotOutlined />, label: <Link to="/system/ai">AI 状态</Link> }] : []),
    ...(hasPermission('ai:config') ? [{ key: '/system/ai-settings', icon: <SettingOutlined />, label: <Link to="/system/ai-settings">AI 设置</Link> }] : []),
    ...(canViewClosing ? [{ key: '/closing', icon: <CalendarOutlined />, label: <Link to="/closing">账期结账</Link> }] : []),
    ...(canViewFeishu ? [{ key: '/feishu', icon: <NotificationOutlined />, label: <Link to="/feishu">飞书配置</Link> }] : []),
  ];

  return [
    ...(hasPermission('dashboard:view') ? [{ key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">工作台</Link> }] : []),
    ...((canViewBankData || canViewTasks || canViewRawMessages || hasPermission('bank:view') || hasPermission('operation:log:view')) ? [{
      key: 'bank-data', icon: <SearchOutlined />, label: '银行数据', children: [
        ...(hasPermission('bank:view') ? [{ key: '/bank-access/accounts', icon: <DatabaseOutlined />, label: <Link to="/bank-access/accounts">银行账户</Link> }] : []),
        ...bankDataChildren,
      ],
    }] : []),
    ...((voucherChildren.length > 0) ? [{
      key: 'voucher-accounting', icon: <ApartmentOutlined />, label: '凭证与入账', children: voucherChildren,
    }] : []),
    ...((systemChildren.length > 0) ? [{
      key: 'system-management', icon: <SettingOutlined />, label: '系统管理', children: systemChildren,
    }] : []),
  ];
}
