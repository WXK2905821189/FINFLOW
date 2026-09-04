import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  // 注意：不要在此处加 rollupOptions.output.manualChunks 手工分桶。
  // 7280e81（frontend code splitting）曾用 manualChunks 把 react 归入 framework、
  // rc-* 归入独立 chunk，导致 rc 库顶层跨 chunk 取 React 为 undefined，
  // 整站白屏（FIX-001，2026-09-02）。构建绿 ≠ 运行绿：改分桶必须 Edge headless 实测首屏。
  // 页面级 code splitting 由 React.lazy 动态 import 实现，不依赖 manualChunks。
  // 共享依赖交给 Rollup 自动分配（v0.1.0 默认打包时浏览器运行正常）。
  //
  // P2-3（2026-09-04）：Shell/Login/Forbidden 已懒加载，首屏从 ~800kB 降至 ~578kB
  // （gzip ~192kB，主 chunk 剩余为 react/react-dom + antd 基座 + 同步守卫链）。
  // 继续压缩只能依赖 manualChunks，已被 FIX-001 白屏教训否决——故将警告线提到 650kB
  // 并记录该取舍，而非调回手工分桶。
  build: {
    chunkSizeWarningLimit: 650,
  },
});
