import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 등록 후 무효화로 다시 읽으므로 창을 옮겨 다닐 때마다 부를 이유가 없다.
      refetchOnWindowFocus: false,
      // 4xx는 다시 불러도 같은 답이다. 재시도해 봐야 실패를 늦게 알게 될 뿐이다.
      retry: false,
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
