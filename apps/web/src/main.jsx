import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { CurrentStockProvider } from './context/CurrentStockContext.jsx';
import { ThemeProvider } from './theme/ThemeProvider.jsx';
import './styles/global.css';
import './styles/tokens.css';

createRoot(document.getElementById('root')).render(
    <React.StrictMode>
        <ThemeProvider>
            <BrowserRouter>
                <CurrentStockProvider>
                    <App/>
                </CurrentStockProvider>
            </BrowserRouter>
        </ThemeProvider>
    </React.StrictMode>
);
