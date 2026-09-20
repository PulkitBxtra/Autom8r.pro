import { backend } from "./client";

export function signup(name: string, email: string, password: string) {
  return backend.post<{ id: string; email: string }>("/signup", {
    name,
    email,
    password,
  });
}

export function login(email: string, password: string) {
  return backend.post<{ token: string }>("/login", { email, password });
}

export function logout(token: string) {
  return backend.post<{ status: string }>("/logout", undefined, token);
}
