import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FileAddOutlined,
  FileSearchOutlined,
  NotificationOutlined,
  RadarChartOutlined,
  RobotOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  TeamOutlined,
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
  '/bank-access/data/reconciliation': '对账核对',
  '/bank-access/raw-messages': '原始报文',
  '/statements/import': '导入流水',
  '/statements/batches': '标准流水',
  '/statements/review': '人工复核',
  '/validation': '科目与往来规则',
  '/statements/vouchers': '金蝶制证',
  '/reconciliation/dashboard': '三方对账',
  '/closing': '结账管理',
  '/users': '用户管理',
  '/system/dicts': '字典中心',
  '/system/ai': 'AI 状态',
  '/audit': '审计中心',
  '/feishu': '飞书协同',
};

type HasPermission = (permission: string) => boolean;

/**
 * 产品导航（2026-09-07 重构方案 WP4，B1 归并 4 组）：
 *  - 「银行数据」升顶层：查询（余额/流水/对账核对）与采集运营（同步任务/运行日志/原始报文）同组，
 *    结束「银行接入 > 数据查询 > 余额查询」三层嵌套；
 *  - 「流水与入账」合并原流水中心 + 金蝶入账两组；
 *  - 低频页（账期结账/三方对账/飞书配置）收纳进系统管理，只挪菜单位置，路由与权限不变。
 */
export function buildProductNavigation(hasPermission: HasPermission): MenuProps['items'] {
  const canViewFeishu = hasPermission('feishu:view') || hasPermission('feishu:manage');
  const canViewTasks = hasPermission('operation:monitor') || hasPermission('bankdata:view');
  const canViewBankData = [
    'bankdata:balance:view', 'bankdata:statement:view',
  ].some(hasPermission);
  const canViewRawMessages = hasPermission('bankdata:raw:view');
  const canViewBankReconciliation = hasPermission('bankdata:view') || hasPermission('bankdata:reconciliation:view');
  const canViewValidation = hasPermission('validation:view') || hasPermission('validation:manage');
  const canViewClosing = hasPermission('closing:view') || hasPermission('closing:manage');

  const bankDataChildren = [
    ...(canViewBankData ? [{ key: '/bank-access/data/balances', label: <Link to="/bank-access/data/balances">余额查询</Link> }] : []),
    ...(canViewBankData ? [{ key: '/bank-access/data/statements', label: <Link to="/bank-access/data/statements">流水查询</Link> }] : []),
    ...(canViewBankReconciliation ? [{ key: '/bank-access/data/reconciliation', label: <Link to="/bank-access/data/reconciliation">对账核对</Link> }] : []),
    ...(canViewTasks ? [{ key: '/bank-access/tasks', icon: <RadarChartOutlined />, label: <Link to="/bank-access/tasks">同步任务</Link> }] : []),
    ...(hasPermission('operation:log:view') ? [{ key: '/bank-access/logs', label: <Link to="/bank-access/logs">运行日志</Link> }] : []),
    // Standalone entry: this is the only surface that shows a bank response in full,
    // so it is deliberately not nested under 数据查询 (which only shows projections).
    ...(canViewRawMessages ? [{ key: '/bank-access/raw-messages', label: <Link to="/bank-access/raw-messages">原始报文</Link> }] : []),
  ];

  const statementChildren = [
    ...(hasPermission('statement:import') ? [{ key: '/statements/import', icon: <FileAddOutlined />, label: <Link to="/statements/import">导入流水</Link> }] : []),
    ...(hasPermission('statement:view') ? [{ key: '/statements/batches', icon: <DatabaseOutlined />, label: <Link to="/statements/batches">标准流水</Link> }] : []),
    ...(hasPermission('statement:review') ? [{ key: '/statements/review', icon: <FileSearchOutlined />, label: <Link to="/statements/review">人工复核</Link> }] : []),
    ...(canViewValidation ? [{ key: '/validation', icon: <SettingOutlined />, label: <Link to="/validation">科目与往来规则</Link> }] : []),
    ...(hasPermission('voucher:push') ? [{ key: '/statements/vouchers', icon: <SendOutlined />, label: <Link to="/statements/vouchers">凭证草稿与制证</Link> }] : []),
  ];

  const systemChildren = [
    ...(hasPermission('user:manage') ? [{ key: '/users', icon: <TeamOutlined />, label: <Link to="/users">用户与角色</Link> }] : []),
    ...(hasPermission('audit:view') ? [{ key: '/audit', icon: <AuditOutlined />, label: <Link to="/audit">审计中心</Link> }] : []),
    ...(hasPermission('system:dict:manage') ? [{ key: '/system/dicts', icon: <DatabaseOutlined />, label: <Link to="/system/dicts">字典中心</Link> }] : []),
    ...(hasPermission('ai:use') ? [{ key: '/system/ai', icon: <RobotOutlined />, label: <Link to="/system/ai">AI 状态</Link> }] : []),
    ...(canViewClosing ? [{ key: '/closing', label: <Link to="/closing">账期结账</Link> }] : []),
    ...(hasPermission('reconciliation:view') ? [{ key: '/reconciliation/dashboard', label: <Link to="/reconciliation/dashboard">三方对账</Link> }] : []),
    ...(canViewFeishu ? [{ key: '/feishu', icon: <NotificationOutlined />, label: <Link to="/feishu">飞书配置</Link> }] : []),
  ];

  return [
    ...(hasPermission('dashboard:view') ? [{ key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">工作台</Link> }] : []),
    ...((canViewBankData || canViewTasks || canViewBankReconciliation || canViewRawMessages || hasPermission('bank:view') || hasPermission('operation:log:view')) ? [{
      key: 'bank-data', icon: <SearchOutlined />, label: '银行数据', children: [
        ...(hasPermission('bank:view') ? [{ key: '/bank-access/accounts', icon: <DatabaseOutlined />, label: <Link to="/bank-access/accounts">银行账户</Link> }] : []),
        ...bankDataChildren,
      ],
    }] : []),
    ...((statementChildren.length > 0) ? [{
      key: 'statement-accounting', icon: <ApartmentOutlined />, label: '流水与入账', children: statementChildren,
    }] : []),
    ...((systemChildren.length > 0) ? [{
      key: 'system-management', icon: <SettingOutlined />, label: '系统管理', children: systemChildren,
    }] : []),
  ];
}
