import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/tokens.css';
import './styles/app.css';

// Native desktop & mobile shell hardening: eliminate all browser artifacts
if (typeof window !== 'undefined') {
  // Prevent browser context menu
  window.addEventListener('contextmenu', (e: MouseEvent) => {
    const target = e.target as HTMLElement | null;
    if (target && target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA' && !target.isContentEditable) {
      e.preventDefault();
    }
  });

  // Prevent browser zoom shortcuts & page reload
  window.addEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === '+' || e.key === '-' || e.key === '=' || e.key === '0')) {
      e.preventDefault();
    }
    if (e.key === 'F5' || ((e.ctrlKey || e.metaKey) && (e.key === 'r' || e.key === 'R'))) {
      e.preventDefault();
    }
  });

  // Prevent Ctrl+Wheel zoom
  window.addEventListener(
    'wheel',
    (e: WheelEvent) => {
      if (e.ctrlKey) e.preventDefault();
    },
    { passive: false }
  );

  // Prevent dragging images or links
  window.addEventListener('dragstart', (e: DragEvent) => {
    const target = e.target as HTMLElement | null;
    if (target && target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
      e.preventDefault();
    }
  });
}

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
