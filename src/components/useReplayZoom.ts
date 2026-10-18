import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type RefObject,
} from "react";

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
 * 全屏回放里的触控板手势缩放：捏合放大画面，放大后双指拖动平移，退出全屏复位；只在 enabled 时接管手势。
 *
 * 一次捏合能推上百个 wheel 事件，所以倍数与偏移累加在 ref 里，每个动画帧只写一次 DOM，
 * React 状态只在「有没有放大」翻转时变一次——把它当逐帧状态用会让整个播放器树跟着重渲染，手势就发涩。
 */
export function useReplayZoom(
  stage: RefObject<HTMLElement | null>,
  video: RefObject<HTMLElement | null>,
  enabled: boolean,
) {
  const view = useRef<View>(FIT);
  const painted = useRef("");
  const frame = useRef(0);
  const [zoomed, setZoomed] = useState(false);

  const paint = useCallback(() => {
    frame.current = 0;
    const node = video.current;
    const box = stage.current?.getBoundingClientRect();
    if (!node || !box) return;
    view.current = panTo(view.current, 0, 0, box.width, box.height);
    const { zoom, x, y } = view.current;
    const transform =
      zoom > MIN_ZOOM ? `translate(${x}px, ${y}px) scale(${zoom})` : "";
    if (transform === painted.current) return;
    node.style.transform = transform;
    painted.current = transform;
    setZoomed(transform !== "");
  }, [stage, video]);

  const queue = useCallback(() => {
    // 同一帧里到达的多个事件合成一次写入。
    if (!frame.current) frame.current = requestAnimationFrame(paint);
  }, [paint]);

  const reset = useCallback(() => {
    if (frame.current) cancelAnimationFrame(frame.current);
    frame.current = 0;
    view.current = FIT;
    paint();
  }, [paint]);

  useEffect(() => {
    if (!enabled) {
      reset();
      return;
    }
    const element = stage.current;
    if (!element) return;
    const onWheel = (event: WheelEvent) => {
      const state = view.current;
      if (event.ctrlKey) {
        view.current = { ...state, zoom: zoomAfter(state.zoom, event) };
      } else if (state.zoom > MIN_ZOOM) {
        // wheel 的 delta 表示滚动量，与手指方向相反，取反后画面才跟着手指走。
        view.current = {
          zoom: state.zoom,
          x: state.x - event.deltaX,
          y: state.y - event.deltaY,
        };
      } else {
        return;
      }
      event.preventDefault();
      queue();
    };
    // React 的合成 onWheel 是 passive 监听，preventDefault 无效，只能自己挂。
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      element.removeEventListener("wheel", onWheel);
      if (frame.current) cancelAnimationFrame(frame.current);
      frame.current = 0;
    };
  }, [enabled, stage, queue, reset]);

  return { zoomed, reset };
}
