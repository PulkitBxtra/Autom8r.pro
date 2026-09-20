"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import type { AuthUser } from "@/lib/types";
import * as authApi from "@/lib/api/auth";

const TOKEN_KEY = "autom8r.token";

function decodeToken(token: string): AuthUser | null {
  try {
    const payload = token.split(".")[1];
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    if (json.exp && Date.now() >= json.exp * 1000) return null;
    return { id: json.sub, email: json.email };
  } catch {
    return null;
  }
}

type AuthContextValue = {
  token: string | null;
  user: AuthUser | null;
  status: "loading" | "authenticated" | "unauthenticated";
  login: (email: string, password: string) => Promise<void>;
  signup: (name: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [status, setStatus] = useState<"loading" | "authenticated" | "unauthenticated">(
    "loading"
  );

  useEffect(() => {
    // Reading localStorage has to happen post-hydration (server has no
    // window), so this can't be a lazy useState initializer without a
    // client/server markup mismatch.
    /* eslint-disable react-hooks/set-state-in-effect */
    const stored = window.localStorage.getItem(TOKEN_KEY);
    if (stored && decodeToken(stored)) {
      setToken(stored);
      setStatus("authenticated");
    } else {
      if (stored) window.localStorage.removeItem(TOKEN_KEY);
      setStatus("unauthenticated");
    }
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  const applyToken = useCallback((next: string) => {
    window.localStorage.setItem(TOKEN_KEY, next);
    setToken(next);
    setStatus("authenticated");
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const { token: newToken } = await authApi.login(email, password);
      applyToken(newToken);
    },
    [applyToken]
  );

  const signup = useCallback(
    async (name: string, email: string, password: string) => {
      await authApi.signup(name, email, password);
      const { token: newToken } = await authApi.login(email, password);
      applyToken(newToken);
    },
    [applyToken]
  );

  const logout = useCallback(async () => {
    if (token) {
      try {
        await authApi.logout(token);
      } catch {
        // token may already be expired/invalid -- clear local state regardless
      }
    }
    window.localStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setStatus("unauthenticated");
  }, [token]);

  const user = useMemo(() => (token ? decodeToken(token) : null), [token]);

  const value = useMemo(
    () => ({ token, user, status, login, signup, logout }),
    [token, user, status, login, signup, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
