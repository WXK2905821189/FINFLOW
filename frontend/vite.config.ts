import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const moduleId = id.replace(/\\/g, '/');
          if (!moduleId.includes('/node_modules/')) return undefined;
          if (moduleId.includes('/react/') || moduleId.includes('/react-dom/') || moduleId.includes('/react-router')) return 'framework';
          if (moduleId.includes('/antd/')) return 'antd';
          if (moduleId.includes('/@ant-design/icons')) return 'antd-icons';
          if (moduleId.includes('/rc-')) return 'rc-components';
          if (moduleId.includes('/axios/') || moduleId.includes('/dayjs/')) return 'data-client';
          return 'vendor';
        },
      },
    },
  },
});
