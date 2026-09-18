import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { usePlayerControls } from "../src/components/usePlayerControls";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it("hides idle fullscreen controls, reveals them on activity, and keeps normal controls visible", () => {
  vi.useFakeTimers();
  const { result, rerender } = renderHook(
    ({ fullscreen }) => usePlayerControls(fullscreen),
    { initialProps: { fullscreen: true } },
  );
  act(() => vi.advanceTimersByTime(2300));
  expect(result.current.visible).toBe(false);
  act(() => result.current.reveal());
  expect(result.current.visible).toBe(true);
  act(() => vi.advanceTimersByTime(2300));
  expect(result.current.visible).toBe(false);
  rerender({ fullscreen: false });
  act(() => vi.advanceTimersByTime(10000));
  expect(result.current.visible).toBe(true);
});

it("does not hide controls during a seek drag, then hides after release", () => {
  vi.useFakeTimers();
  const { result } = renderHook(() => usePlayerControls(true));
  act(() => result.current.hold());
  act(() => vi.advanceTimersByTime(10000));
  expect(result.current.visible).toBe(true);
  act(() => window.dispatchEvent(new Event("pointerup")));
  act(() => vi.advanceTimersByTime(2300));
  expect(result.current.visible).toBe(false);
});
