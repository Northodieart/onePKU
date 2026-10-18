// Windows 的捏合要 WebView2 开着缩放控制才会作为带 Ctrl 的滚轮进页面；一开这条通道，宿主也会拿它缩放整页。
// 所以这里取消带 Ctrl 的滚轮默认行为：界面自己不缩放，捏合的用途交给回放画面。Ctrl 加减号的键盘缩放保留。
export function guardPageZoom() {
  const block = (event: WheelEvent) => {
    if (event.ctrlKey) event.preventDefault();
  };
  // 必须绑在 window 上并用非 passive 监听，绑 document 时部分浏览器不认这个取消。
  window.addEventListener("wheel", block, { passive: false, capture: true });
  return () => window.removeEventListener("wheel", block, { capture: true });
}
