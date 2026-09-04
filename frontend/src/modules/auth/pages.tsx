import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store/auth';
import { PageLoading } from '../shared/components';

export function AuthGuard() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();
  if (status === 'restoring') return <PageLoading />;
  return status === 'authenticated' ? <Outlet /> : <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
}

export function PermissionGuard({ permissions }: { permissions: string[] }) {
  const hasPermission = useAuthStore((state) => state.hasPermission);
  return permissions.some(hasPermission) ? <Outlet /> : <Navigate to="/403" replace />;
}

// Login / Forbidden 已迁至独立文件 LoginPage.tsx / ForbiddenPage.tsx（懒加载，P2-3 首屏优化）。
