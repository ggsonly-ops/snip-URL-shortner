import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, NavLink, Navigate } from 'react-router-dom';

import ShortenPage from './pages/ShortenPage.jsx';
import LinksPage from './pages/LinksPage.jsx';
import AnalyticsPage from './pages/AnalyticsPage.jsx';
import StatusBar from './components/StatusBar.jsx';
import './styles.css';

// SPA routes all live under /app/* apart from "/", because every other single path
// segment at the root belongs to the backend as a short code. Nginx routes on exactly
// that split.
function App() {
  return (
    <div className="shell">
      <header className="top">
        <NavLink to="/" className="brand">
          snip <small>distributed URL shortener</small>
        </NavLink>
        <nav className="top-nav">
          <NavLink to="/" end>Shorten</NavLink>
          <NavLink to="/app/links">My links</NavLink>
        </nav>
      </header>

      <Routes>
        <Route path="/" element={<ShortenPage />} />
        <Route path="/app/links" element={<LinksPage />} />
        <Route path="/app/analytics/:code" element={<AnalyticsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>

      <StatusBar />
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
