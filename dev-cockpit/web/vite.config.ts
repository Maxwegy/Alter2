import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Built by Gradle (:dev-cockpit:webBuild) into build/web/static, which Ktor serves at /.
// `npm run dev` proxies /api to a running cockpit for UI work without rebuilding the jar.
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: '../build/web/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:43600', changeOrigin: false },
    },
  },
})
