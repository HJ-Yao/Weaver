import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { router } from './app/router';
import './shared/styles/tokens.css';
import './shared/styles/base.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('missing #root element in index.html');
}

createRoot(container).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
