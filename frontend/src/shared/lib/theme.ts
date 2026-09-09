import { create } from 'zustand';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'sbm.theme';

function readInitialTheme(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'dark' ? '#0c1210' : '#0e7466');
}

const initialTheme = readInitialTheme();
applyTheme(initialTheme);

export const useTheme = create<{
  theme: Theme;
  setTheme: (theme: Theme) => void;
}>((set) => ({
  theme: initialTheme,
  setTheme: (theme) => {
    applyTheme(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      /* Storage is optional. */
    }
    set({ theme });
  },
}));
