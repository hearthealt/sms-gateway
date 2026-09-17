import { post } from './http'
import type { LoginResult } from '../types'

export function login(username: string, password: string): Promise<LoginResult> {
  return post('/admin/auth/login', { username, password })
}

export function logout(): Promise<void> {
  return post('/admin/auth/logout')
}
