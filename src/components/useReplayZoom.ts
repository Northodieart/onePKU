import { useEffect, useRef, useState, type RefObject } from "react";

/** 全屏里的缩放范围：1 倍就是原始画面，再往上放大只剩块状了。 */
const MIN_ZOOM = 1;
const MAX_ZOOM = 4;
/** 捏合距离换算成倍数的比例，数值越大同样的手势缩得越少。 */
const SENSITIVITY = 240;

type View = { zoom: number; x: number; y: number };
const FIT: View = { zoom: 1, x: 0, y: 0 };

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

/**
 * 触控板捏合到浏览器里是带 ctrlKey 的 wheel 事件。按指数换算，来回捏同样的距离能回到原倍数。
 */
export function zoomAfter(
  zoom: number,
  event: { deltaY: number; deltaMode?: number },
) {
  const pixels = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY;
  return clamp(zoom * Math.exp(-pixels / SENSITIVITY), MIN_ZOOM, MAX_ZOOM);
}

/** 平移不能超过多出来的那半圈画面，否则视频会被整个拖出窗口。 */
export function panTo(
  view: View,
  dx: number,
  dy: number,
  width: number,
  height: number,
): View {
  const maxX = Math.max(0, (width * view.zoom - width) / 2);
  const maxY = Math.max(0, (height * view.zoom - height) / 2);
  return {
    zoom: view.zoom,
    x: clamp(view.x + dx, -maxX, maxX),
    y: clamp(view.y + dy, -maxY, maxY),
  };
}

/**
 * 全屏回放里的触控板手势缩放：只在全屏启用，捏合放大画面，放大后双指拖动平移，退出全屏复位。
 * 非全屏不拦截手势，浏览器的页面缩放照旧。
 */
export function useReplayZoom(
  stage: RefObject<HTMLElement | null>,
  enabled: boolean,
) {
  const [view, setView] = useState<View>(FIT);
  const current = useRef(view);
  current.current = view;

  useEffect(() => {
    if (!enabled) {
      setView(FIT);
      return;
    }
    const element = stage.current;
    if (!element) return;
    const onWheel = (event: WheelEvent) => {
      const { width, height } = element.getBoundingClientRect();
      const state = current.current;
      if (event.ctrlKey) {
        const zoom = zoomAfter(state.zoom, event);
        // 改变倍数后原来的平移量可能就越界了，按新倍数重新夹一次。
        setView(panTo({ ...state, zoom }, 0, 0, width, height));
        event.preventDefault();
      } else if (state.zoom > MIN_ZOOM) {
        // wheel 的 delta 表示"滚动量"，手指往下给的是负值；画面要跟着手指走就得取反。
        setView(panTo(state, -event.deltaX, -event.deltaY, width, height));
        event.preventDefault();
      }
    };
    // React 的合成 onWheel 是 passive 监听，preventDefault 无效，只能自己挂。
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => element.removeEventListener("wheel", onWheel);
  }, [enabled, stage]);

  return {
    zoom: view.zoom,
    reset: () => setView(FIT),
    transform:
      enabled && view.zoom > MIN_ZOOM
        ? `translate(${view.x}px, ${view.y}px) scale(${view.zoom})`
        : undefined,
  };
}
