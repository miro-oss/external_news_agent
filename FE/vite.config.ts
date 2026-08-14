import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 서버(5173)와 백엔드(8080)는 오리진이 다르다. 여기서 넘겨 같은 오리진처럼 보이게 한다.
    // 백엔드에 CORS 설정을 넣지 않는 이유다 — 운영에서는 같은 오리진으로 서빙되므로 필요가 없고,
    // 개발 편의로 서버에 출처 허용을 남겨 두면 그게 그대로 배포된다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
