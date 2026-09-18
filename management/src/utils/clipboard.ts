/**
 * 复制文本到剪贴板，返回是否成功，调用方据此决定提示文案。
 *
 * 优先用 navigator.clipboard，但它只在安全上下文（https / localhost）下才存在。
 * 管理后台常按 IP 走 http 直接访问，此时 navigator.clipboard 是 undefined，
 * 点「复制验证码」必失败 —— 而取码正是这个后台最核心的操作，不能因为部署方式就废掉。
 * 故再兜一层 execCommand('copy')：它虽已废弃，却是 http 下唯一还能用的办法。
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 安全上下文下也可能被拒（缺少用户手势、被权限策略拦截），继续走兜底
    }
  }
  return copyWithExecCommand(text)
}

function copyWithExecCommand(text: string): boolean {
  // execCommand 复制的是当前选区，所以临时节点必须真的挂进 DOM 并选中：
  // display:none / visibility:hidden 的节点根本选不中，只能挪到视口外
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '-9999px'
  document.body.appendChild(textarea)

  // 记下用户原有选区，用完还原，否则页面上正选中的内容会被这次复制清掉
  const selection = document.getSelection()
  const previousRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  textarea.select()
  // iOS Safari 上 select() 对 textarea 不生效，要再显式框选一次
  textarea.setSelectionRange(0, textarea.value.length)

  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }

  document.body.removeChild(textarea)
  if (previousRange && selection) {
    selection.removeAllRanges()
    selection.addRange(previousRange)
  }
  return ok
}
