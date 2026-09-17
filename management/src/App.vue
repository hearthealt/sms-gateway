<template>
  <router-view />
</template>

<script setup lang="ts">
</script>

<style>
:root {
  --color-primary: #409eff;
  --color-primary-dark: #2d7dd2;
  --color-success: #67c23a;
  --color-warning: #e6a23c;
  --color-danger: #f56c6c;
  --color-info: #909399;
  --color-bg: #f0f2f5;
  --color-bg-dark: #e8ebf0;
  --color-text-primary: #303133;
  --color-text-regular: #606266;
  --color-text-secondary: #909399;
  --color-text-placeholder: #c0c4cc;
  --color-border: #e4e7ed;
  --color-border-light: #ebeef5;
  --color-white: #fff;
  --sidebar-width: 220px;
  --sidebar-width-collapsed: 64px;
  --sidebar-bg: #1d2b3a;
  --sidebar-text: #bfcbd9;
  --sidebar-active: #409eff;
  --header-height: 60px;
  --border-radius-base: 8px;
  --border-radius-small: 4px;
  --shadow-base: 0 1px 4px rgba(0,0,0,0.06);
  --shadow-hover: 0 4px 16px rgba(0,0,0,0.08);
  --shadow-card: 0 1px 8px rgba(0,0,0,0.04);
  --transition-base: all 0.25s ease;
  --transition-fast: all 0.15s ease;
}

*, *::before, *::after {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  width: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans SC', sans-serif;
  color: var(--color-text-primary);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-thumb { background: var(--color-text-placeholder); border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: var(--color-text-secondary); }
::-webkit-scrollbar-track { background: transparent; }

::selection { background: var(--color-primary); color: var(--color-white); }

a { color: var(--color-primary); text-decoration: none; }
a:hover { color: var(--color-primary-dark); }

.fade-enter-active, .fade-leave-active { transition: opacity 0.25s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
