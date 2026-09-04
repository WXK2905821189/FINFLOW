import { lazy, Suspense, useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './styles.css';
import { useAuthStore } from './store/auth';
import { AuthGuard, Forbidden, Login, PermissionGuard } from './modules/auth/pages';
import { Shell } from './modules/shell/Shell';
import { PageLoading, PreservedFinancePage } from './modules/shared/components';

const Dashboard = lazy(() => import('./modules/dashboard/pages').then((module) => ({ default: module.Dashboard })));
const BatchList = lazy(() => import('./modules/statements/pages').then((module) => ({ default: module.BatchList })));
const ImportStatements = lazy(() => import('./modules/statements/pages').then((module) => ({ default: module.ImportStatements })));
const Reconciliation = lazy(() => import('./modules/statements/pages').then((module) => ({ default: module.Reconciliation })));
const ReviewStatements = lazy(() => import('./modules/statements/pages').then((module) => ({ default: module.ReviewStatements })));
const VoucherStatements = lazy(() => import('./modules/statements/pages').then((module) => ({ default: module.VoucherStatements })));
const ValidationPage = lazy(() => import('./modules/statements/ValidationPage').then((module) => ({ default: module.ValidationPage })));
const BankAccountPage = lazy(() => import('./modules/bank-access/pages').then((module) => ({ default: module.BankAccountPage })));
const ConnectionConfigurationPage = lazy(() => import('./modules/bank-access/pages').then((module) => ({ default: module.ConnectionConfigurationPage })));
const ConnectionMonitoring = lazy(() => import('./modules/bank-access/pages').then((module) => ({ default: module.ConnectionMonitoring })));
const OperationLogs = lazy(() => import('./modules/bank-access/operations').then((module) => ({ default: module.OperationLogs })));
const OperationTasks = lazy(() => import('./modules/bank-access/operations').then((module) => ({ default: module.OperationTasks })));
const BankDataQueryPage = lazy(() => import('./modules/bank-access/BankDataQueryPage').then((module) => ({ default: module.BankDataQueryPage })));
const RawMessagesPage = lazy(() => import('./modules/bank-access/RawMessagesPage').then((module) => ({ default: module.RawMessagesPage })));
const FeishuCollaboration = lazy(() => import('./modules/feishu/pages').then((module) => ({ default: module.FeishuCollaboration })));
const ClosingPage = lazy(() => import('./modules/closing/pages').then((module) => ({ default: module.ClosingPage })));
const AuditCenterPage = lazy(() => import('./modules/audit/pages').then((module) => ({ default: module.AuditCenterPage })));

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
            <Route path="/users" element={<PreservedFinancePage title="用户管理" description="用户管理入口已保留，银行接入权限不提升用户管理权限。" />} />
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
          <Route element={<PermissionGuard permissions={['statement:import']} />}>
            <Route path="/statements/import" element={<ImportStatements />} />
          </Route>
          <Route element={<PermissionGuard permissions={['statement:view']} />}>
            <Route path="/statements/batches" element={<BatchList />} />
          </Route>
          <Route element={<PermissionGuard permissions={['statement:review']} />}>
            <Route path="/statements/review" element={<ReviewStatements />} />
          </Route>
          <Route element={<PermissionGuard permissions={['voucher:push']} />}>
            <Route path="/statements/vouchers" element={<VoucherStatements />} />
          </Route>
          <Route element={<PermissionGuard permissions={['reconciliation:view']} />}>
            <Route path="/reconciliation/dashboard" element={<Reconciliation />} />
          </Route>
          <Route element={<PermissionGuard permissions={['connection:view', 'connection:manage']} />}>
            <Route path="/bank-access/connections" element={<ConnectionConfigurationPage section="applications" title="银行连接" description="管理真实银行直联连接的受控元数据；密钥仅存于服务端环境变量，不落库、不返回。" />} />
            <Route path="/bank-access/agreements" element={<ConnectionConfigurationPage section="contracts" title="签约准备与采集设置" description="展示签约准备度与采集配置；签约生效以银行侧开通为准，浏览器不读取或保存密钥、证书或令牌。" />} />
          </Route>
          <Route element={<PermissionGuard permissions={['bank:view']} />}>
            <Route path="/bank-access/accounts" element={<BankAccountPage />} />
          </Route>
          <Route element={<PermissionGuard permissions={['operation:monitor']} />}>
            <Route path="/bank-access/monitoring" element={<ConnectionMonitoring />} />
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
          <Route path="/connections/apps" element={<Navigate to="/bank-access/connections" replace />} />
          <Route path="/connections/agreements" element={<Navigate to="/bank-access/agreements" replace />} />
          <Route path="/connections/preferences" element={<Navigate to="/bank-access/agreements" replace />} />
          <Route path="/operations/connectivity" element={<Navigate to="/bank-access/monitoring" replace />} />
          <Route path="/operations/tasks" element={<Navigate to="/bank-access/tasks" replace />} />
          <Route path="/operations/logs" element={<Navigate to="/bank-access/logs" replace />} />
          <Route path="/bank-data/balances" element={<Navigate to="/bank-access/data/balances" replace />} />
          <Route path="/bank-data/statements" element={<Navigate to="/bank-access/data/statements" replace />} />
          <Route path="/statements/reconciliation" element={<Navigate to="/reconciliation/dashboard" replace />} />
          <Route path="/bank-access/preferences" element={<Navigate to="/bank-access/agreements" replace />} />
          <Route path="/403" element={<Forbidden />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
    </Suspense>
  );
}

export default function App() {
  return <BrowserRouter><AppRoutes /></BrowserRouter>;
}
