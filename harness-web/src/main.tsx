import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles.css'

const root = document.getElementById('root')
if (root === null) throw new Error('缺少 #root 挂载点')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
