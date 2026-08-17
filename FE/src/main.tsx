import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from './api/client.ts'
import './index.css'
import App from './App.tsx'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 등록 후 무효화로 다시 읽으므로 창을 옮겨 다닐 때마다 부를 이유가 없다.
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // 4xx는 다시 불러도 같은 답이다. 네트워크 오류와 5xx만 짧게 재시도한다.
        if (error instanceof ApiError && error.status !== null && error.status >= 400 && error.status < 500) {
          return false
        }
        return failureCount < 2
      },
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 5000),
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
