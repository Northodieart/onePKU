import { useEffect, useRef, useState } from "react";
import type { PDFDocumentProxy, RenderTask } from "pdfjs-dist";
import { call } from "../lib/api";
import { loadPdf } from "../lib/materialPdf";
export default function MaterialPreview({
  course,
  id,
  generation,
  page: initialPage = 1,
  onPageChange,
}: {
  course: string;
  id: string;
  generation: string;
  page?: number;
  onPageChange?: (page: number) => void;
}) {
  const [doc, setDoc] = useState<PDFDocumentProxy>();
  const [data, setData] = useState<{ mime: string; base64: string }>();
  const [error, setError] = useState("");
  const [page, setPage] = useState(initialPage);
  const [width, setWidth] = useState(500);
  const canvas = useRef<HTMLCanvasElement>(null);
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    setPage(initialPage);
  }, [initialPage]);
  useEffect(() => {
    let stopped = false;
    let task: ReturnType<typeof loadPdf> | undefined;
    setData(undefined);
    setDoc(undefined);
    setError("");
    void call<{ mime: string; base64: string }>({
      kind: "readLocalMaterial",
      course,
      id,
    })
      .then(async (env) => {
        if (stopped) return;
        if (env.generation !== generation)
          throw Error("账号已变化，请刷新搜索");
        if (env.error || !env.data)
          throw Error(env.error?.message ?? "无法读取资料");
        setData(env.data);
        if (env.data.mime === "application/pdf") {
          task = loadPdf(env.data.base64);
          const pdf = await task.promise;
          if (!stopped) setDoc(pdf);
        }
      })
      .catch((e) => {
        if (!stopped) setError(e.message);
      });
    return () => {
      stopped = true;
      void task?.destroy();
    };
  }, [course, id, generation]);
  useEffect(() => {
    const el = container.current;
    if (!el) return;
    const observer = new ResizeObserver((entries) =>
      setWidth(entries[0].contentRect.width),
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!doc || !canvas.current) return;
    let cancelled = false;
    let render: RenderTask | undefined;
    void doc
      .getPage(Math.min(page, doc.numPages))
      .then((p) => {
        if (cancelled || !canvas.current) return;
        const original = p.getViewport({ scale: 1 });
        const viewport = p.getViewport({
          scale: Math.max(100, width - 32) / original.width,
        });
        const ratio = Math.min(devicePixelRatio || 1, 2);
        const c = canvas.current;
        c.width = viewport.width * ratio;
        c.height = viewport.height * ratio;
        c.style.width = `${viewport.width}px`;
        c.style.height = `${viewport.height}px`;
        render = p.render({
          canvas: c,
          viewport,
          transform: [ratio, 0, 0, ratio, 0, 0],
        });
        return render.promise;
      })
      .catch((e) => {
        if (!cancelled && e.name !== "RenderingCancelledException")
          setError("这一页未能显示，请重新打开资料");
      });
    return () => {
      cancelled = true;
      render?.cancel();
    };
  }, [doc, page, width]);
  return (
    <div className="search-file-preview" ref={container}>
      {error ? (
        <p role="alert" className="inline-error">
          {error}
        </p>
      ) : !data ? (
        <p className="subtle">正在打开资料…</p>
      ) : data.mime === "application/pdf" ? (
        <>
          {doc && (
            <div className="search-page-controls">
              <button
                disabled={page <= 1}
                onClick={() => {
                  setPage(page - 1);
                  onPageChange?.(page - 1);
                }}
              >
                上一页
              </button>
              <span>
                第 {page} / {doc.numPages} 页
              </span>
              <button
                disabled={page >= doc.numPages}
                onClick={() => {
                  setPage(page + 1);
                  onPageChange?.(page + 1);
                }}
              >
                下一页
              </button>
            </div>
          )}
          <canvas ref={canvas} aria-label={`资料第 ${page} 页`} />
        </>
      ) : data.mime.startsWith("image/") ? (
        <img alt="资料预览" src={`data:${data.mime};base64,${data.base64}`} />
      ) : (
        <pre>
          {new TextDecoder().decode(
            Uint8Array.from(atob(data.base64), (c) => c.charCodeAt(0)),
          )}
        </pre>
      )}
    </div>
  );
}
