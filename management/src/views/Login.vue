<template>
  <div class="login-container">
    <!-- 左：品牌区（窄屏隐藏，见样式里的断点） -->
    <aside class="login-brand">
      <div class="brand-bg">
        <div class="bg-orb bg-orb-1"></div>
        <div class="bg-orb bg-orb-2"></div>
        <div class="bg-orb bg-orb-3"></div>
      </div>
      <div class="brand-content">
        <div class="brand-logo">
          <svg viewBox="0 0 32 32" width="44" height="44" fill="none">
            <rect width="32" height="32" rx="8" fill="url(#logoGradient)" />
            <path d="M8 16L14 22L24 10" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            <defs><linearGradient id="logoGradient" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#409eff"/><stop offset="1" stop-color="#36d399"/></linearGradient></defs>
          </svg>
        </div>
        <h1>SMS Gateway</h1>
        <p class="brand-sub">短信网关管理后台</p>
        <ul class="brand-features">
          <li v-for="feature in features" :key="feature">
            <el-icon><CircleCheck /></el-icon>
            <span>{{ feature }}</span>
          </li>
        </ul>
      </div>
    </aside>

    <!-- 右：表单区 -->
    <main class="login-panel">
      <!-- 窄屏时的品牌占位：与左栏二选一显示，保证手机上仍看得到 logo -->
      <div class="panel-brand">
        <svg viewBox="0 0 32 32" width="34" height="34" fill="none">
          <rect width="32" height="32" rx="8" fill="url(#logoGradientSm)" />
          <path d="M8 16L14 22L24 10" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
          <defs><linearGradient id="logoGradientSm" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#409eff"/><stop offset="1" stop-color="#36d399"/></linearGradient></defs>
        </svg>
        <div class="panel-brand-text">
          <strong>SMS Gateway</strong>
          <span>短信网关管理后台</span>
        </div>
      </div>

      <div class="login-form-wrap">
        <h2 class="form-title">登录</h2>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @keyup.enter="handleLogin"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              :prefix-icon="User"
              size="large"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
              size="large"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              size="large"
              :loading="loading"
              class="login-btn"
              @click="handleLogin"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </el-button>
          </el-form-item>
        </el-form>
        <div class="login-footer">© {{ year }} SMS Gateway</div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, CircleCheck } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { login } from '../api/auth'
import { saveSession } from '../utils/auth'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const year = new Date().getFullYear()

/** 左侧品牌区的卖点，和产品实际能力对齐，别写成凑数的空话。 */
const features = [
  '设备集中管理',
  '验证码自动提取',
  '采集规则可配',
]

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  // 回车事件挂在 el-form 上，按钮的 :loading 管不到键盘 ——
  // 没有这道闸，慢网络下连按回车会连发几次登录请求
  if (loading.value) return
  if (!formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await login(form.username, form.password)
    saveSession(result.token, result.displayName || result.username, result.expiresIn)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch {
    // 失败提示由 http 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/*
 * 左右分栏：左侧品牌（45%）+ 右侧表单（55%）。
 * 两栏比例用 flex-basis 定死，不用 grid —— 窄屏要让左栏整体消失，
 * flex 下一条 display: none 就够了，grid 还得重排模板列。
 */
.login-container {
  display: flex;
  min-height: 100vh;
}

/* ═══════════ 左：品牌区 ═══════════ */
.login-brand {
  position: relative;
  flex: 0 0 45%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  overflow: hidden;
}

.brand-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #0f0c29 0%, #302b63 50%, #24243e 100%);
  overflow: hidden;
}

/* 动态光斑：保留原来的观感，但只在这半屏里飘 */
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.35;
  animation: orbFloat 12s ease-in-out infinite;
}
.bg-orb-1 {
  width: 500px;
  height: 500px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  top: -150px;
  right: -100px;
}
.bg-orb-2 {
  width: 400px;
  height: 400px;
  /* 品牌绿 → 主色，就是 logo 渐变那一对 */
  background: linear-gradient(135deg, var(--color-accent), var(--color-primary));
  bottom: -100px;
  left: -100px;
  animation-delay: -4s;
}
.bg-orb-3 {
  width: 300px;
  height: 300px;
  background: linear-gradient(135deg, var(--color-danger), var(--color-warning));
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  animation-delay: -8s;
}
@keyframes orbFloat {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25% { transform: translate(30px, -40px) scale(1.05); }
  50% { transform: translate(-20px, 20px) scale(0.95); }
  75% { transform: translate(40px, 30px) scale(1.02); }
}

.brand-content {
  position: relative;
  z-index: 1;
  max-width: 360px;
  color: var(--color-white);
}
.brand-logo {
  margin-bottom: 20px;
  display: flex;
}
.brand-content h1 {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 0.5px;
  margin: 0 0 8px;
}
.brand-sub {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.72);
  margin: 0 0 40px;
}
.brand-features {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.brand-features li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.86);
}
.brand-features .el-icon {
  font-size: 16px;
  color: var(--color-accent);
  flex-shrink: 0;
}

/* ═══════════ 右：表单区 ═══════════ */
.login-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  background: var(--color-white);
}

/* 窄屏才出现，桌面端由左栏承担品牌信息 */
.panel-brand {
  display: none;
}
.panel-brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.panel-brand-text strong {
  font-size: 16px;
  color: var(--color-text-primary);
}
.panel-brand-text span {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.login-form-wrap {
  width: 100%;
  max-width: 360px;
}
.form-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-text-primary);
  margin: 0 0 28px;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  border-radius: var(--border-radius-base);
  transition: var(--transition-base);
  letter-spacing: 4px;
}

.login-footer {
  text-align: center;
  margin-top: 28px;
  font-size: 12px;
  color: var(--color-text-placeholder);
}

/* Override element-plus form item margin in scoped */
:deep(.el-form-item) {
  margin-bottom: 22px;
}
:deep(.el-input__wrapper) {
  border-radius: var(--border-radius-base);
  box-shadow: 0 0 0 1px var(--color-border) inset;
  transition: var(--transition-fast);
}
:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}

/*
 * 窄屏（含平板竖屏）收起左栏：45% 的宽度在 900px 以下会把表单挤到 360px 以内，
 * 输入框和按钮都变得局促。此时改用表单上方的小号品牌行，logo 不丢。
 */
@media (max-width: 900px) {
  .login-brand {
    display: none;
  }
  .login-panel {
    padding: 40px 24px;
  }
  .panel-brand {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 32px;
  }
}
</style>
