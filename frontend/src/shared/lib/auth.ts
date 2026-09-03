import { create } from 'zustand';

// Credentials intentionally have no persistence middleware or browser storage.
export const useSessionAuth = create<{
  token: string;
  setToken: (token: string) => void;
}>((set) => ({ token: '', setToken: (token) => set({ token: token.trim() }) }));
export const authHeaders = (): Record<string, string> => {
  const token = useSessionAuth.getState().token;
  return token ? { Authorization: `Bearer ${token}` } : {};
};
