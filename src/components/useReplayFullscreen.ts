import { useEffect, useRef, useState, type RefObject } from "react";
import {
  getCurrentWindow,
  type Window as NativeWindow,
} from "@tauri-apps/api/window";

export function useReplayFullscreen(
  frame: RefObject<HTMLDivElement | null>,
  reportError: (message: string) => void,
) {
  const [active, setActive] = useState(false);
  const session = useRef<{
    window: NativeWindow;
    previous: boolean;
    entered: boolean;
  } | null>(null);
  const changing = useRef(false);
  const mounted = useRef(true);

  async function exit() {
    const current = session.current;
    if (!current || changing.current) return;
    changing.current = true;
    try {
      await current.window.setFullscreen(current.previous);
      session.current = null;
      setActive(false);
    } catch {
      reportError("未能退出全屏，请重试");
    } finally {
      changing.current = false;
    }
  }

  useEffect(() => {
    mounted.current = true;
    const webChanged = () =>
      setActive(document.fullscreenElement === frame.current);
    const resized = () => {
      const current = session.current;
      if (!current || changing.current) return;
      void current.window
        .isFullscreen()
        .then((fullscreen) => {
          if (session.current !== current) return;
          if (fullscreen) current.entered = true;
          else if (current.entered) {
            session.current = null;
            setActive(false);
          }
        })
        .catch(() => {});
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape" && session.current) {
        event.preventDefault();
        event.stopPropagation();
        void exit();
      }
    };
    document.addEventListener("fullscreenchange", webChanged);
    window.addEventListener("resize", resized);
    window.addEventListener("keydown", key, true);
    return () => {
      mounted.current = false;
      document.removeEventListener("fullscreenchange", webChanged);
      window.removeEventListener("resize", resized);
      window.removeEventListener("keydown", key, true);
      const current = session.current;
      session.current = null;
      if (current)
        void current.window.setFullscreen(current.previous).catch(() => {});
    };
  }, [frame]);

  // Keep keyboard navigation inside the player while the rest of the app is covered.
  useEffect(() => {
    if (!active || !frame.current) return;
    const hidden: Array<[HTMLElement, boolean]> = [];
    let node: HTMLElement = frame.current;
    while (node.parentElement && node.parentElement !== document.body) {
      for (const sibling of node.parentElement.children) {
        if (sibling !== node && sibling instanceof HTMLElement) {
          hidden.push([sibling, sibling.inert]);
          sibling.inert = true;
        }
      }
      node = node.parentElement;
    }
    return () =>
      hidden.forEach(([element, inert]) => {
        element.inert = inert;
      });
  }, [active, frame]);

  async function toggle() {
    if (changing.current) return;
    if (session.current) return exit();
    if (!("__TAURI_INTERNALS__" in window)) {
      try {
        if (document.fullscreenElement) await document.exitFullscreen();
        else await frame.current?.requestFullscreen();
      } catch {
        reportError("全屏未能打开，请重试");
      }
      return;
    }
    changing.current = true;
    try {
      const window = getCurrentWindow();
      const previous = await window.isFullscreen();
      if (!mounted.current) return;
      const current = { window, previous, entered: previous };
      session.current = current;
      await window.setFullscreen(true);
      if (!mounted.current) {
        await window.setFullscreen(previous);
        return;
      }
      setActive(true);
      frame.current?.querySelector("video")?.focus({ preventScroll: true });
      current.entered = await window.isFullscreen();
    } catch {
      session.current = null;
      reportError("全屏未能打开，请重试");
    } finally {
      changing.current = false;
    }
  }
  return { active, toggle, native: active && session.current !== null };
}
