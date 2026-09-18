import React from 'react'; import ReactDOM from 'react-dom/client'; import dayjs from 'dayjs'; import 'dayjs/locale/zh-cn'; import App from './App';

// 2026-09-17：DatePicker 面板的月份/星期文案来自 dayjs locale，antd 的 zh_CN 不覆盖它。
dayjs.locale('zh-cn');

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
