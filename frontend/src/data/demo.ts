import type { Transaction, User } from '../types';
export const transactions: Transaction[] = [
  { id: 'TX20260826001', date: '2026-08-26 10:20', bank: '中信银行', counterparty: '杭州云帆科技有限公司', summary: '8 月云服务费用', amount: -12800, type: '支出', status: '已入账' },
  { id: 'TX20260825002', date: '2026-08-25 15:42', bank: '中信银行', counterparty: '北京远景咨询有限公司', summary: '项目服务收入', amount: 56800, type: '收入', status: '已入账' },
  { id: 'TX20260823003', date: '2026-08-23 09:15', bank: '中信银行', counterparty: '上海启程供应链有限公司', summary: '采购结算款', amount: -23600, type: '支出', status: '已入账' },
  { id: 'TX20260822004', date: '2026-08-22 17:36', bank: '中信银行', counterparty: '南京星河文化有限公司', summary: '品牌咨询收入', amount: 32000, type: '收入', status: '已入账' }
];
export const users: User[] = [
  { id: 1, username: 'admin', email: 'admin@finflow.local', phone: '138****0000', status: '启用', roles: ['系统管理员'], permissions: ['dashboard:view', 'user:manage', 'transfer:create', 'transaction:view'] },
  { id: 2, username: 'zhang.wei', email: 'zhang.wei@finflow.local', phone: '139****3210', status: '启用', roles: ['财务专员'], permissions: ['dashboard:view', 'transfer:create', 'transaction:view'] },
  { id: 3, username: 'li.na', email: 'li.na@finflow.local', phone: '137****8820', status: '启用', roles: ['财务经理'], permissions: ['dashboard:view', 'transaction:view'] }
];
