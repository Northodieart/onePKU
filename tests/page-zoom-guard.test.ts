import { afterEach, expect, it, vi } from "vitest";
import { guardPageZoom } from "../src/lib/pageZoomGuard";

afterEach(() => vi.restoreAllMocks());

const wheel = (init: WheelEventInit) =>
  new WheelEvent("wheel", { bubbles: true, cancelable: true, ...init });

it("cancels ctrl+wheel so the app UI never page-zooms", () => {
  const stop = guardPageZoom();
  // Windows 的捏合是以带 Ctrl 的滚轮送进页面的，宿主拿它做整页缩放；这里把默认行为取消。
  const pinch = wheel({ ctrlKey: true, deltaY: -8 });
  window.dispatchEvent(pinch);
  expect(pinch.defaultPrevented).toBe(true);
  // 普通双指滚动不受影响，页面照常滚。
  const scroll = wheel({ deltaY: 120 });
  window.dispatchEvent(scroll);
  expect(scroll.defaultPrevented).toBe(false);
  stop();
  const released = wheel({ ctrlKey: true, deltaY: -8 });
  window.dispatchEvent(released);
  expect(released.defaultPrevented).toBe(false);
});
