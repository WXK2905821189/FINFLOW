import { useState } from 'react';
import { Avatar, Button, Drawer, Dropdown, Layout, Menu, type MenuProps } from 'antd';
import { LogoutOutlined, MenuOutlined, UserOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/auth';
import { buildProductNavigation, pageTitles, PRODUCT_MENU_STORAGE_KEY } from '../navigation/productNavigation';

const { Header, Sider, Content } = Layout;

export function Shell() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout, hasPermission } = useAuthStore();
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [openKeys, setOpenKeys] = useState<string[]>(() => {
    try {
      const saved = window.localStorage.getItem(PRODUCT_MENU_STORAGE_KEY);
      return saved ? JSON.parse(saved) as string[] : ['bank-access'];
    } catch {
      return ['bank-access'];
    }
  });
  const menuItems = buildProductNavigation(hasPermission);
  const logoutAndRedirect = () => {
    logout();
    navigate('/login');
  };
  const handleMenuOpenChange = (nextKeys: string[]) => {
    setOpenKeys(nextKeys);
    try {
      window.localStorage.setItem(PRODUCT_MENU_STORAGE_KEY, JSON.stringify(nextKeys));
    } catch {
      // Menu state is a convenience preference; storage failure must not block navigation.
    }
  };
  const closeMobileNavigation: MenuProps['onClick'] = ({ key }) => {
    if (String(key).startsWith('/')) setMobileNavigationOpen(false);
  };
  return <Layout className="app-shell"><Sider breakpoint="lg" collapsedWidth="64" collapsible><div className="brand"><div className="brand-mark">F</div><span>FINFLOW</span></div><div className="workspace-label">企业财务工作台</div><Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} openKeys={openKeys} onOpenChange={handleMenuOpenChange} items={menuItems} /></Sider><Layout><Header className="topbar"><div className="topbar-title"><Button className="mobile-navigation-button" type="text" aria-label="打开导航" icon={<MenuOutlined />} onClick={() => setMobileNavigationOpen(true)} /><div><div className="eyebrow">FINFLOW / 企业财务工作台</div><h1>{pageTitles[location.pathname] || '财务工作台'}</h1></div></div><Dropdown menu={{ items: [{ key: 'profile', label: user?.email || '个人资料', icon: <UserOutlined /> }, { type: 'divider' }, { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, onClick: logoutAndRedirect }] }}><Button type="text" className="profile-button"><Avatar size={32} icon={<UserOutlined />} /><span>{user?.username}</span></Button></Dropdown></Header><Content className="page-content"><Outlet /></Content></Layout><Drawer className="mobile-navigation" title="导航" placement="left" width={320} open={mobileNavigationOpen} onClose={() => setMobileNavigationOpen(false)}><Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} openKeys={openKeys} onOpenChange={handleMenuOpenChange} onClick={closeMobileNavigation} items={menuItems} /></Drawer></Layout>;
}
