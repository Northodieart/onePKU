import React from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import ReplayPlayer from "../src/components/ReplayPlayer";
import { panTo, zoomAfter } from "../src/components/useReplayZoom";

const native = vi.hoisted(() => ({
  isFullscreen: vi.fn(),
  setFullscreen: vi.fn(),
}));
const invoke = vi.hoisted(() => vi.fn());
vi.mock("@tauri-apps/api/window", () => ({ getCurrentWindow: () => native }));
vi.mock("@tauri-apps/api/core", () => ({ invoke }));

const cache = {
  completed: 3,
  segments: 719,
  bytes: 1000000,
  complete: false,
  downloading: false,
  offline: false,
  message: "",
};
const envelope = (data: unknown) => ({
  data,
  error: null,
  stale: false,
  warnings: [],
  generation: "account",
  updatedAt: new Date().toISOString(),
});

beforeEach(() => {
  vi.stubGlobal("__TAURI_INTERNALS__", {});
  vi.spyOn(HTMLMediaElement.prototype, "canPlayType").mockReturnValue(
    "probably",
  );
  vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
  invoke.mockImplementation(async (_command: string, args?: unknown) => {
    const kind = (args as { request: { kind: string } })?.request?.kind;
    if (kind === "playbackPrepare")
      return envelope({
        id: "session",
        url: "http://127.0.0.1:4567/media/token/index.m3u8",
        title: "Lecture",
        duration: 300,
        status: cache,
      });
    return envelope(cache);
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

function scaleOf(video: HTMLVideoElement) {
  return Number(/scale\(([\d.]+)\)/.exec(video.style.transform)?.[1] ?? 1);
}
function offsetOf(video: HTMLVideoElement) {
  const matched = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)/.exec(
    video.style.transform,
  );
  return { x: Number(matched?.[1] ?? 0), y: Number(matched?.[2] ?? 0) };
}

it("pinches to zoom only in fullscreen, pans when zoomed, and restores on exit", async () => {
  native.isFullscreen.mockResolvedValueOnce(false).mockResolvedValue(true);
  native.setFullscreen.mockResolvedValue(undefined);
  render(
    <ReplayPlayer
      course="1"
      video={{
        title: "Lecture",
        time: "2026-09-08",
        url: "https://onlineroomse.pku.edu.cn/livingroom",
        hash_id: "abc",
      }}
      generation="account"
      close={() => {}}
    />,
  );
  const video = screen.getByLabelText("Lecture 视频") as HTMLVideoElement;
  const stage = video.parentElement as HTMLElement;
  vi.spyOn(stage, "getBoundingClientRect").mockReturnValue({
    width: 800,
    height: 600,
  } as DOMRect);
  // 播放会话没就绪前全屏按钮是禁用的，先等它可用再点。
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "全屏", exact: true }),
    ).toBeEnabled(),
  );

  // ① 非全屏不接管手势：捏合仍归浏览器页面缩放，画面不动。
  const outside = new WheelEvent("wheel", {
    bubbles: true,
    cancelable: true,
    ctrlKey: true,
    deltaY: -300,
  });
  stage.dispatchEvent(outside);
  expect(outside.defaultPrevented).toBe(false);
  expect(video.style.transform).toBe("");

  fireEvent.click(screen.getByRole("button", { name: "全屏", exact: true }));
  await screen.findByRole("button", { name: "退出全屏" });

  // ② 全屏里双指捏合放大，并吃掉默认行为，不让 WebView 跟着缩整页。
  const pinch = new WheelEvent("wheel", {
    bubbles: true,
    cancelable: true,
    ctrlKey: true,
    deltaY: -300,
  });
  stage.dispatchEvent(pinch);
  await waitFor(() => expect(scaleOf(video)).toBeGreaterThan(1));
  expect(pinch.defaultPrevented).toBe(true);
  expect(scaleOf(video)).toBeLessThanOrEqual(4);
  const zoom = scaleOf(video);

  // ③ 放大后双指拖动要跟着手指走：自然滚动里手指往下是负的 deltaY，画面应往下（正偏移）。
  stage.dispatchEvent(
    new WheelEvent("wheel", {
      bubbles: true,
      cancelable: true,
      deltaY: -99999,
    }),
  );
  await waitFor(() => expect(offsetOf(video).y).toBeGreaterThan(0));
  expect(offsetOf(video).y).toBeCloseTo((600 * zoom - 600) / 2, 3);
  // 反向往左上拖，回到另外一侧的上限，越不出多出来的那半圈。
  stage.dispatchEvent(
    new WheelEvent("wheel", {
      bubbles: true,
      cancelable: true,
      deltaX: 99999,
      deltaY: 99999,
    }),
  );
  await waitFor(() =>
    expect(offsetOf(video).y).toBeCloseTo(-(600 * zoom - 600) / 2, 3),
  );
  expect(offsetOf(video).x).toBeCloseTo(-(800 * zoom - 800) / 2, 3);

  // ④ 控制栏出现复位按钮，点一下回到原尺寸。
  fireEvent.click(screen.getByRole("button", { name: "复位画面" }));
  await waitFor(() => expect(video.style.transform).toBe(""));
  expect(
    screen.queryByRole("button", { name: "复位画面" }),
  ).not.toBeInTheDocument();

  // ⑤ 退出全屏自己复位：放大后退出，画面回到原尺寸，捏合也不再被接管。
  stage.dispatchEvent(
    new WheelEvent("wheel", {
      bubbles: true,
      cancelable: true,
      ctrlKey: true,
      deltaY: -300,
    }),
  );
  await waitFor(() => expect(scaleOf(video)).toBeGreaterThan(1));
  fireEvent.click(screen.getByRole("button", { name: "退出全屏" }));
  await waitFor(() => expect(video.style.transform).toBe(""));
  const afterExit = new WheelEvent("wheel", {
    bubbles: true,
    cancelable: true,
    ctrlKey: true,
    deltaY: -300,
  });
  stage.dispatchEvent(afterExit);
  expect(afterExit.defaultPrevented).toBe(false);
});

it("explains the gesture in the playback options", async () => {
  native.isFullscreen.mockResolvedValue(true);
  native.setFullscreen.mockResolvedValue(undefined);
  render(
    <ReplayPlayer
      course="1"
      video={{
        title: "Lecture",
        time: "2026-09-08",
        url: "https://onlineroomse.pku.edu.cn/livingroom",
        hash_id: "abc",
      }}
      generation="account"
      close={() => {}}
    />,
  );
  fireEvent.click(
    screen.getByRole("button", { name: "更多播放选项", exact: true }),
  );
  expect(
    screen.getByText(/全屏时双指捏合放大画面（1–4 倍）/),
    // 文案要提到放大后能平移、退出全屏会复位，免得用户以为画面卡住了。
  ).toHaveTextContent("退出全屏自动回到原尺寸");
});

it("clamps pinch zoom between the original picture and four times", () => {
  expect(zoomAfter(1, { deltaY: -10000 })).toBe(4);
  expect(zoomAfter(4, { deltaY: 10000 })).toBe(1);
  expect(zoomAfter(2, { deltaY: -100 })).toBeCloseTo(
    2 * Math.exp(100 / 240),
    3,
  );
  expect(zoomAfter(2, { deltaY: 100 })).toBeCloseTo(
    2 * Math.exp(-100 / 240),
    3,
  );
  // 来回捏同样的距离应该回得到原倍数。
  expect(
    zoomAfter(zoomAfter(2, { deltaY: -100 }), { deltaY: 100 }),
  ).toBeCloseTo(2, 3);
  // 行模式（部分浏览器的鼠标滚轮）折算成像素，免得一格就顶到上限。
  expect(zoomAfter(1, { deltaY: -3, deltaMode: 1 })).toBeCloseTo(
    Math.exp(48 / 240),
    3,
  );
});

it("keeps a zoomed picture from being dragged out of the frame", () => {
  const zoomed = { zoom: 2, x: 0, y: 0 };
  expect(panTo(zoomed, 0, 9999, 800, 600)).toEqual({ zoom: 2, x: 0, y: 300 });
  expect(panTo(zoomed, -9999, 0, 800, 600)).toEqual({ zoom: 2, x: -400, y: 0 });
  // 1 倍时没有可平移的余量，残留的偏移要被清掉。
  expect(panTo({ zoom: 1, x: 200, y: 100 }, 0, 0, 800, 600).x).toBe(0);
});
