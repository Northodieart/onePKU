import { useCallback, useEffect, useRef, useState } from "react";

export function usePlayerControls(fullscreen: boolean) {
  const [visible, setVisible] = useState(true);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const dragging = useRef(false);
  const reveal = useCallback(() => {
    clearTimeout(timer.current);
    setVisible(true);
    if (fullscreen && !dragging.current)
      timer.current = setTimeout(() => setVisible(false), 2200);
  }, [fullscreen]);
  useEffect(() => {
    reveal();
    const release = () => {
      dragging.current = false;
      reveal();
    };
    window.addEventListener("pointerup", release);
    window.addEventListener("pointercancel", release);
    return () => {
      clearTimeout(timer.current);
      window.removeEventListener("pointerup", release);
      window.removeEventListener("pointercancel", release);
    };
  }, [reveal]);
  return {
    visible: !fullscreen || visible,
    reveal,
    hold: () => {
      dragging.current = true;
      clearTimeout(timer.current);
      setVisible(true);
    },
  };
}
