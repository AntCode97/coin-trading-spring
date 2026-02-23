const TOKEN_KEY = 'web_desktop_token';

export function getWebToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setWebToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearWebToken(): void {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function hasWebToken(): boolean {
  const token = sessionStorage.getItem(TOKEN_KEY);
  return token !== null && token.length > 0;
}
