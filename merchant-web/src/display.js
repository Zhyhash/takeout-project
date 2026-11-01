// 展示辅助函数。
// 状态文字一律展示后端返回的 statusDesc，不在前端臆造文案。
// 状态数值与 OrderStatusEnum 对应：2 PAID、3 FINISHED、4 CANCELLED、5 PREPARING、6 READY、7 DELIVERING、8 DELIVERED。
export function statusTagType(status) {
  switch (status) {
    case 2:
      return 'warning'
    case 5:
    case 7:
      return 'primary'
    case 3:
    case 6:
    case 8:
      return 'success'
    case 4:
      return 'danger'
    default:
      return 'info'
  }
}

export function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

export function amountText(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? amount.toFixed(2) : String(value ?? '—')
}
