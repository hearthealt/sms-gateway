/**
 * 管理员会话在本地的存放。
 *
 * 令牌放 localStorage（跨标签页共享、关掉浏览器不丢），但**同时记下过期时刻**。
 *
 * 此前只判「有没有」：服务端 TTL（默认 2 小时）一到，界面看起来一切正常 ——
 * 侧边栏还在、页面还在，点什么都失败。用户完全不知道发生了什么，
 * 只知道「后台坏了」。有了过期时刻，路由守卫和请求拦截器都能在过期时
 * 直接引导去重新登录，而不是等一次失败请求。
 *
 * 刻意不做「刷新令牌」：管理后台的会话本来就不该无限续期，过期重新登录一次是合理的。
 */

const TOKEN_KEY = 'token'
const USERNAME_KEY = 'username'
const EXPIRES_AT_KEY = 'tokenExpiresAt'

export function saveSession(token: string, username: string, expiresInSeconds: number) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
  localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() + expiresInSeconds * 1000))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(EXPIRES_AT_KEY)
}

/**
 * 取一个**未过期**的令牌；不存在或已过期返回 null，并把残留清干净。
 *
 * 没有过期时刻的会话（本次改动之前登录、还留在浏览器里的）按有效处理 ——
 * 那种情况只能交给服务端的 401 收尾，总比把所有在线用户踢下线好。
 */
export function getValidToken(): string | null {
  const token = localStorage.getItem(TOKEN_KEY)
  if (!token) return null

  const raw = localStorage.getItem(EXPIRES_AT_KEY)
  if (!raw) return token

  const expiresAt = Number(raw)
  if (!Number.isFinite(expiresAt) || Date.now() >= expiresAt) {
    clearSession()
    return null
  }
  return token
}

export function getUsername(): string {
  return localStorage.getItem(USERNAME_KEY) ?? ''
}

/** 仅供登录后立刻取用，不要在别处读它 —— 用 getValidToken()。 */
export function getRawToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
