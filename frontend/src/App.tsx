import { lazy, Suspense, useEffect } from 'react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './styles.css';

// antd 5 的静态方法（Modal.confirm / Modal.info 等）不读取最近的 <ConfigProvider>，它们走独立的
// holderRender 通道。缺这段则弹窗按钮恒为英文 Cancel/OK（2026-09-17 UI 点击测试 R2 截图实证）。
// 组件式 antd 组件由下方 <ConfigProvider locale> 覆盖。
ConfigProvider.config({
  holderRender: (children) => <ConfigProvider locale={zhCN}>{children}</ConfigProvider>,
});
import { useAuthStore } from './store/auth';
import { AuthGuard, PermissionGuard } from './modules/auth/pages';
import { PageLoading } from './modules/shared/components';

// Shell/Login/Forbidden 均懒加载：登录首屏只下载登录所需依赖；antd 控制台骨架（Layout/Menu
// 与 Result 403 页）登录后才按需取。页面级 code splitting 一律走 React.lazy，不引入
// manualChunks（FIX-001 白屏教训）。
const Shell = lazy(() => import('./modules/shell/Shell').then((module) => ({ default: module.Shell })));
const Login = lazy(() => import('./modules/auth/LoginPage').then((module) => ({ default: module.Login })));
const Forbidden = lazy(() => import('./modules/auth/ForbiddenPage').then((module) => ({ default: module.Forbidden })));

const Dashboard = lazy(() => import('./modules/dashboard/pages').then((module) => ({ default: module.Dashboard })));
const ValidationPage = lazy(() => import('./modules/statements/ValidationPage').then((module) => ({ default: module.ValidationPage })));
const BankAccountPage = lazy(() => import('./modules/bank-access/pages').then((module) => ({ default: module.BankAccountPage })));
const OperationLogs = lazy(() => import('./modules/bank-access/operations').then((module) => ({ default: module.OperationLogs })));
const OperationTasks = lazy(() => import('./modules/bank-access/operations').then((module) => ({ default: module.OperationTasks })));
const BankDataQueryPage = lazy(() => import('./modules/bank-access/BankDataQueryPage').then((module) => ({ default: module.BankDataQueryPage })));
const RawMessagesPage = lazy(() => import('./modules/bank-access/RawMessagesPage').then((module) => ({ default: module.RawMessagesPage })));
const FeishuCollaboration = lazy(() => import('./modules/feishu/pages').then((module) => ({ default: module.FeishuCollaboration })));
const ClosingPage = lazy(() => import('./modules/closing/pages').then((module) => ({ default: module.ClosingPage })));
const AuditCenterPage = lazy(() => import('./modules/audit/pages').then((module) => ({ default: module.AuditCenterPage })));
const UserAdminPage = lazy(() => import('./modules/admin/UsersPage').then((module) => ({ default: module.UserAdminPage })));
const DictionaryPage = lazy(() => import('./modules/admin/DictionaryPage').then((module) => ({ default: module.DictionaryPage })));
const AiSettingsPage = lazy(() => import('./modules/admin/AiSettingsPage').then((module) => ({ default: module.AiSettingsPage })));
const AiStatusPage = lazy(() => import('./modules/admin/AiStatusPage').then((module) => ({ default: module.AiStatusPage })));
const VoucherCenterPage = lazy(() => import('./modules/voucher/VoucherCenterPage').then((module) => ({ default: module.VoucherCenterPage })));
const VoucherDocPage = lazy(() => import('./modules/voucher/VoucherDocPage').then((module) => ({ default: module.VoucherDocPage })));
const CategoryRulesPage = lazy(() => import('./modules/voucher/CategoryRulesPage').then((module) => ({ default: module.CategoryRulesPage })));

function AppRoutes() {
  const hydrate = useAuthStore((state) => state.hydrate);
  useEffect(() => { void hydrate(); }, [hydrate]);
  return (
    <Suspense fallback={<PageLoading />}>
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<AuthGuard />}>
        <Route element={<Shell />}>
          <Route element={<PermissionGuard permissions={['dashboard:view']} />}>
            <Route path="/dashboard" element={<Dashboard />} />
          </Route>
          <Route element={<PermissionGuard permissions={['user:manage']} />}>
            <Route path="/users" element={<UserAdminPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['system:dict:manage']} />}>
            <Route path="/system/dicts" element={<DictionaryPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['ai:config']} />}>
            <Route path="/system/ai-settings" element={<AiSettingsPage />} />
          </Route>
          {/* V32：AI 状态与设置仅超管（ai:config）可见；成员通过 ai:use 使用能力端点，不感知网关配置。 */}
          <Route element={<PermissionGuard permissions={['ai:config']} />}>
            <Route path="/system/ai" element={<AiStatusPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['audit:view']} />}>
            <Route path="/audit" element={<AuditCenterPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['validation:view', 'validation:manage']} />}>
            <Route path="/validation" element={<ValidationPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['closing:view', 'closing:manage']} />}>
            <Route path="/closing" element={<ClosingPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['feishu:view', 'feishu:manage']} />}>
            <Route path="/feishu" element={<FeishuCollaboration />} />
          </Route>
          {/* V34 ⑦：/statements/vouchers 换为「凭证中心」（凭证一等视图 + 单据详情 + 打印），
              原「凭证草稿与制证」组件由凭证中心承载的行内通过/驳回/推送操作取代。 */}
          <Route element={<PermissionGuard permissions={['voucher:push']} />}>
            <Route path="/statements/vouchers" element={<VoucherCenterPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['voucher:push']} />}>
            <Route path="/statements/voucher-doc/:statementId" element={<VoucherDocPage />} />
          </Route>
          {/* V34 ②：大类规则（kingdee_voucher_rule 只读清单，规则维护走迁移）。 */}
          <Route element={<PermissionGuard permissions={['voucher:push']} />}>
            <Route path="/voucher-rules" element={<CategoryRulesPage />} />
          </Route>
          {/* 已下线页面（2026-09-16 流程精简 + 2026-09-17 V34 ⑥ 三方对账并入工作台）：
              导入流水/标准流水/人工复核/三方对账/对账核对各页删除或并入。 */}
          <Route path="/statements/import" element={<Navigate to="/bank-access/data/statements" replace />} />
          <Route path="/statements/batches" element={<Navigate to="/statements/vouchers" replace />} />
          <Route path="/statements/review" element={<Navigate to="/bank-access/data/statements" replace />} />
          <Route path="/reconciliation/dashboard" element={<Navigate to="/dashboard" replace />} />
          <Route path="/statements/reconciliation" element={<Navigate to="/dashboard" replace />} />
          <Route element={<PermissionGuard permissions={['bank:view']} />}>
            <Route path="/bank-access/accounts" element={<BankAccountPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['operation:monitor', 'bankdata:view']} />}>
            <Route path="/bank-access/tasks" element={<OperationTasks />} />
          </Route>
          <Route element={<PermissionGuard permissions={['operation:log:view']} />}>
            <Route path="/bank-access/logs" element={<OperationLogs />} />
          </Route>
          <Route element={<PermissionGuard permissions={['bankdata:balance:view']} />}>
            <Route path="/bank-access/data/balances" element={<BankDataQueryPage resource="balances" />} />
          </Route>
          <Route element={<PermissionGuard permissions={['bankdata:statement:view']} />}>
            <Route path="/bank-access/data/statements" element={<BankDataQueryPage resource="statements" />} />
          </Route>
          <Route element={<PermissionGuard permissions={['bankdata:raw:view']} />}>
            <Route path="/bank-access/raw-messages" element={<RawMessagesPage />} />
          </Route>
          {/* 已下线页面的旧路径统一重定向：接入配置两页并入银行账户，连接监控并入采集任务 */}
          <Route path="/bank-access/connections" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/bank-access/agreements" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/bank-access/preferences" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/bank-access/monitoring" element={<Navigate to="/bank-access/tasks" replace />} />
          <Route path="/connections/apps" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/connections/agreements" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/connections/preferences" element={<Navigate to="/bank-access/accounts" replace />} />
          <Route path="/operations/connectivity" element={<Navigate to="/bank-access/tasks" replace />} />
          <Route path="/operations/tasks" element={<Navigate to="/bank-access/tasks" replace />} />
          <Route path="/operations/logs" element={<Navigate to="/bank-access/logs" replace />} />
          <Route path="/bank-data/balances" element={<Navigate to="/bank-access/data/balances" replace />} />
          <Route path="/bank-data/statements" element={<Navigate to="/bank-access/data/statements" replace />} />
          <Route path="/bank-access/data/reconciliation" element={<Navigate to="/dashboard" replace />} />
          <Route path="/403" element={<Forbidden />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
    </Suspense>
  );
}

export default function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter><AppRoutes /></BrowserRouter>
    </ConfigProvider>
  );
}
