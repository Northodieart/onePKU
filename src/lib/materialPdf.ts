import * as pdfjs from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { WorkerMessageHandler } from "pdfjs-dist/build/pdf.worker.min.mjs";
pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
if (location.protocol === "tauri:")
  (globalThis as unknown as { pdfjsWorker: unknown }).pdfjsWorker = {
    WorkerMessageHandler,
  };
export function loadPdf(base64: string) {
  return pdfjs.getDocument({
    data: Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
    useSystemFonts: true,
    cMapUrl: new URL("/pdfjs/cmaps/", location.href).href,
    cMapPacked: true,
    standardFontDataUrl: new URL("/pdfjs/standard_fonts/", location.href).href,
    wasmUrl: new URL("/pdfjs/wasm/", location.href).href,
  });
}
export async function pdfText(base64: string, stopped: () => boolean) {
  const task = loadPdf(base64);
  try {
    const doc = await task.promise;
    const pages: { page: number; text: string }[] = [];
    for (let page = 1; page <= Math.min(doc.numPages, 400); page++) {
      if (stopped()) break;
      const p = await doc.getPage(page);
      // WKWebView supports stream readers, but some shipped versions do not
      // implement ReadableStream[Symbol.asyncIterator] used by getTextContent.
      const reader = p.streamTextContent().getReader();
      const parts: string[] = [];
      try {
        while (!stopped()) {
          const { value, done } = await reader.read();
          if (done) break;
          for (const item of value.items)
            if ("str" in item)
              parts.push(item.str + (item.hasEOL ? "\n" : " "));
        }
        if (stopped()) await reader.cancel();
      } finally {
        reader.releaseLock();
      }
      const text = parts.join("").trim();
      if (text) pages.push({ page, text });
      p.cleanup();
    }
    return { pages, truncated: doc.numPages > 400 };
  } finally {
    await task.destroy();
  }
}
