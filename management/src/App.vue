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
  --transition-base: all 0.25s ease;
  --transition-fast: all 0.15s ease;
  /*
   * 位移类动画的标准缓动（前段快、后段从容收住）。
   * ease 前段太急后段太拖，横向位移用这条更贴合；侧边栏折叠、
   * 抽屉滑入、图标翻转都用它。时长各自定 —— 不同距离该用不同时长。
   */
  --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);

  /* ── 品牌与内容色 ── */
  /*
   * 品牌强调绿：logo 渐变第二站、登录页光斑、特性勾选图标。
   * 它和 --color-primary 一起用于装饰，不是状态色 —— 表示「成功」请用 --color-success。
   */
  --color-accent: #36d399;
  /* 主色浅底，对齐 Element Plus 的 --el-color-primary-light-9。标签、提示条、选中态共用。 */
  --color-primary-bg: #ecf5ff;
  /* 成功 / 警告 / 危险的浅底，同样对齐 EP 的 light-9，用于徽标、提示块与图标块 */
  --color-success-bg: #f0f9eb;
  --color-warning-bg: #fdf6ec;
  --color-danger-bg: #fef0f0;
  /* 深色代码块。与 --sidebar-bg(#1d2b3a) 同族但偏蓝一档：侧栏是导航，代码块是内容。 */
  --color-code-bg: #1e2a3a;
  --color-code-text: #e6e9ef;

  /* ── 尺度 ── */
  /*
   * 主内容区内边距。窄屏在文件末尾的断点里改这两个值 ——
   * ApiDocs 的高度 calc() 也读它，改一处即可，不必第二处同步。
   */
  --main-padding-y: 24px;
  --main-padding-x: 28px;
  /* 页面级纵向节奏：卡片与卡片之间。 */
  --section-gap: 24px;
  /* 卡片内边距。必须是单值，理由见下面 .el-card 那段注释。 */
  --card-padding: 24px;
  /* 胶囊圆角，用于徽标这类「反正是圆的」元素。 */
  --border-radius-pill: 999px;

  /* ── 表格专用色 ── */
  /*
   * 表头只比白底深一丝。原先各页把表头设成 --color-bg(#f0f2f5)，
   * 那恰好是 Element Plus 色阶里最重的一档（--el-fill-color），
   * 又和页面底色同色 —— 卡片顶上横着一条灰石板，很压。
   */
  --table-header-bg: #f7f8fa;
  --table-stripe-bg: #fcfcfd;
  /* 行悬停给一点主色味，比默认的中性灰更容易跟住鼠标在哪一行 */
  --table-hover-bg: #f2f7fd;
}

/*
 * Element Plus 把 --el-card-* 声明在 .el-card 自身上（不是 :root），
 * 所以覆盖必须落在同一个选择器上 —— 写进 :root 会被 .el-card 自己的值压过去。
 * main.ts 先 import element-plus 的 css、后 import 本文件，同特异性下靠源序生效；
 * 若哪天 import 顺序变了，改成 .el-card.el-card 提高特异性即可。
 *
 * --el-card-padding 必须给单值：EP 用 calc(var(--el-card-padding) - 2px) 算卡片头/脚的
 * 纵向内边距，写成 "20px 24px" 会让那条 calc 失效、整条 padding 声明作废 ——
 * DeviceDetail 的卡片头内边距会无声塌成 0。
 *
 * 顺带把项目与 EP 的边框色命名接上：EP 的 --el-border-color-light 是 #e4e7ed
 * （= 本项目的 --color-border），-lighter 才是 #ebeef5（= 本项目的 --color-border-light），
 * 两者恰好反着。各页原先手写的 border: 1px solid var(--color-border-light) 并非冗余，
 * 它是在把 EP 默认的 #e4e7ed 调浅，所以这里显式接上，各页那份就能删了。
 */
.el-card {
  --el-card-border-radius: var(--border-radius-base);
  --el-card-border-color: var(--color-border-light);
  --el-card-padding: var(--card-padding);
}

/*
 * ═══════════════ 表格 ═══════════════
 * 四个列表页的 el-table 统一吃这一套，各页不再各写一份 :deep() 覆盖。
 * 变量同样声明在 .el-table 上（EP 也是在 .el-table 上声明的），靠源序压过它。
 */
.el-table {
  --el-table-header-bg-color: var(--table-header-bg);
  --el-table-header-text-color: var(--color-text-regular);
  /* EP 默认是 --el-border-color-lighter(#ebeef5)，正好等于本项目的 --color-border-light */
  --el-table-border-color: var(--color-border-light);
  --el-table-row-hover-bg-color: var(--table-hover-bg);
}

/*
 * 表头降到 13px、压松字距，和数据行拉开主次。
 * 排除 size="small"：接口文档页的参考表刻意做密，那里保持 12px。
 */
.el-table:not(.el-table--small) th.el-table__cell > .cell {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

/*
 * 行高松开。EP 的单元格是 padding: 8px 0 配 23px 行高，行净高约 40px，
 * 数据一多就挤成一片；加到 13px 后约 50px。
 * 只加在数据行，表头保持紧凑，否则会头重脚轻；
 * 排除展开行（.el-table__expanded-cell）—— 它自己有内边距，再加就重复了。
 */
.el-table:not(.el-table--small) .el-table__body td.el-table__cell:not(.el-table__expanded-cell) {
  padding-top: 13px;
  padding-bottom: 13px;
}

/*
 * 斑马纹直接改选择器，而不是覆盖 --el-fill-color-lighter：
 * 那个变量被 EP 其他组件共用（表格里就有 el-switch 的禁用态），
 * 设在 .el-table 上会顺带把它们一起改掉。
 */
.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell {
  background-color: var(--table-stripe-bg);
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

/* ═══════════════ 全局断点 ═══════════════ */

/*
 * 1200px 是「侧边栏自动收起」的同一条线（见 Layout.vue）：
 * 主区留白同时收紧，两者一起改才不显得偏。
 * 改这里的 --main-padding-y 会连带 ApiDocs 的 calc(100vh - …) 一起变。
 */
@media (max-width: 1200px) {
  :root {
    --main-padding-y: 16px;
    --main-padding-x: 20px;
  }
}

@media (max-width: 768px) {
  :root {
    --main-padding-y: 12px;
    --main-padding-x: 14px;
    --header-height: 56px;
  }
}

/*
 * 分页器窄屏收起「共 N 条」和「条/页」：一行放不下会折行错位。
 * 三个列表页共用这一条，不必各写一份。
 */
@media (max-width: 640px) {
  .el-pagination .el-pagination__total,
  .el-pagination .el-pagination__sizes { display: none; }
}
</style>
