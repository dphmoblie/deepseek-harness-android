import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 产物挂在 dsh webserver 根路径（http://127.0.0.1:3080/）。
export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    host: '127.0.0.1',
    port: 4174,
    strictPort: false,
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    outDir: 'dist',
  },
})
