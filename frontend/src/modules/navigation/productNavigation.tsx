import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FileAddOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  NotificationOutlined,
  RadarChartOutlined,
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
  '/bank-access/tasks': '采集任务',
  '/bank-access/logs': '采集失败日志',
  '/bank-access/data/balances': '余额查询',
  '/bank-access/data/statements': '流水查询',
  '/bank-access/raw-messages': '原始报文',
  '/statements/import': '导入流水',
  '/statements/batches': '标准流水',
  '/statements/review': '人工复核',
  '/validation': '科目与往来规则',
  '/statements/vouchers': '金蝶制证',
  '/reconciliation/dashboard': '三方对账',
  '/closing': '结账管理',
  '/users': '用户管理',
  '/audit': '审计中心',
  '/feishu': '飞书协同',
};

type HasPermission = (permission: string) => boolean;

export function buildProductNavigation(hasPermission: HasPermission): MenuProps['items'] {
  const canViewFeishu = hasPermission('feishu:view') || hasPermission('feishu:manage');
  const canViewTasks = hasPermission('operation:monitor') || hasPermission('bankdata:view');
  const canViewBankData = [
    'bankdata:balance:view', 'bankdata:statement:view',
  ].some(hasPermission);
  const canViewRawMessages = hasPermission('bankdata:raw:view');

  return [
    ...(hasPermission('dashboard:view') ? [{ key: '/dashboard', icon: <DashboardOutlined />, label: <Link to="/dashboard">工作台</Link> }] : []),
    ...((hasPermission('operation:monitor') || hasPermission('operation:log:view') || canViewTasks || canViewBankData || canViewRawMessages || hasPermission('bank:view')) ? [{
      key: 'bank-access', icon: <ApartmentOutlined />, label: '银行接入', children: [
        ...(hasPermission('bank:view') ? [{ key: '/bank-access/accounts', icon: <DatabaseOutlined />, label: <Link to="/bank-access/accounts">银行账户</Link> }] : []),
        ...(canViewTasks ? [{ key: '/bank-access/tasks', icon: <RadarChartOutlined />, label: <Link to="/bank-access/tasks">采集任务</Link> }] : []),
        ...(hasPermission('operation:log:view') ? [{ key: '/bank-access/logs', label: <Link to="/bank-access/logs">采集失败日志</Link> }] : []),
        ...(canViewBankData ? [{ key: 'bank-access-data', icon: <SearchOutlined />, label: '数据查询', children: [
          ...(hasPermission('bankdata:balance:view') ? [{ key: '/bank-access/data/balances', label: <Link to="/bank-access/data/balances">余额查询</Link> }] : []),
          ...(hasPermission('bankdata:statement:view') ? [{ key: '/bank-access/data/statements', label: <Link to="/bank-access/data/statements">流水查询</Link> }] : []),
        ] }] : []),
        // Standalone entry: this is the only surface that shows a bank response in full,
        // so it is deliberately not nested under 数据查询 (which only shows projections).
        ...(canViewRawMessages ? [{ key: '/bank-access/raw-messages', icon: <FileTextOutlined />, label: <Link to="/bank-access/raw-messages">原始报文</Link> }] : []),
      ],
    }] : []),
    ...((hasPermission('statement:import') || hasPermission('statement:view') || hasPermission('statement:review')) ? [{
      key: 'statement-center', icon: <DatabaseOutlined />, label: '流水中心', children: [
        ...(hasPermission('statement:import') ? [{ key: '/statements/import', icon: <FileAddOutlined />, label: <Link to="/statements/import">导入流水</Link> }] : []),
        ...(hasPermission('statement:view') ? [{ key: '/statements/batches', icon: <DatabaseOutlined />, label: <Link to="/statements/batches">标准流水</Link> }] : []),
        ...(hasPermission('statement:review') ? [{ key: '/statements/review', icon: <FileSearchOutlined />, label: <Link to="/statements/review">人工复核</Link> }] : []),
      ],
    }] : []),
    ...((hasPermission('voucher:push') || hasPermission('validation:view') || hasPermission('validation:manage')) ? [{
      key: 'kingdee-accounting', icon: <SendOutlined />, label: '金蝶入账', children: [
        ...((hasPermission('validation:view') || hasPermission('validation:manage')) ? [{ key: '/validation', icon: <SettingOutlined />, label: <Link to="/validation">科目与往来规则</Link> }] : []),
        ...(hasPermission('voucher:push') ? [{ key: '/statements/vouchers', icon: <SendOutlined />, label: <Link to="/statements/vouchers">凭证草稿与制证</Link> }] : []),
      ],
    }] : []),
    ...((hasPermission('reconciliation:view') || hasPermission('closing:view') || hasPermission('closing:manage')) ? [{
      key: 'reconciliation-and-closing', icon: <AuditOutlined />, label: '对账结账', children: [
        ...(hasPermission('reconciliation:view') ? [{ key: '/reconciliation/dashboard', label: <Link to="/reconciliation/dashboard">三方对账</Link> }] : []),
        ...((hasPermission('closing:view') || hasPermission('closing:manage')) ? [{ key: '/closing', label: <Link to="/closing">账期结账</Link> }] : []),
      ],
    }] : []),
    ...((hasPermission('user:manage') || hasPermission('audit:view') || canViewFeishu) ? [{
      key: 'system-management', icon: <SettingOutlined />, label: '系统管理', children: [
        ...(hasPermission('user:manage') ? [{ key: '/users', icon: <TeamOutlined />, label: <Link to="/users">用户与角色</Link> }] : []),
        ...(canViewFeishu ? [{ key: '/feishu', icon: <NotificationOutlined />, label: <Link to="/feishu">飞书配置</Link> }] : []),
        ...(hasPermission('audit:view') ? [{ key: '/audit', icon: <AuditOutlined />, label: <Link to="/audit">审计中心</Link> }] : []),
      ],
    }] : []),
  ];
}
