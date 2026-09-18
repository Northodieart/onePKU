import { it, expect, vi } from "vitest";
const state = vi.hoisted(() => ({ getDocument: vi.fn() }));
vi.mock("pdfjs-dist", () => ({
  getDocument: state.getDocument,
  GlobalWorkerOptions: {},
}));
vi.mock("pdfjs-dist/build/pdf.worker.min.mjs?url", () => ({
  default: "worker.mjs",
}));
vi.mock("pdfjs-dist/build/pdf.worker.min.mjs", () => ({
  WorkerMessageHandler: {},
}));
import { pdfText } from "../src/lib/materialPdf";
it("extracts Chinese text using a stream reader when WebView lacks async stream iteration", async () => {
  const releaseLock = vi.fn();
  const destroy = vi.fn();
  const read = vi
    .fn()
    .mockResolvedValueOnce({
      done: false,
      value: {
        items: [
          { str: "巴拿赫", hasEOL: false },
          { str: "问题", hasEOL: true },
        ],
      },
    })
    .mockResolvedValueOnce({ done: true });
  // No Symbol.asyncIterator, matching the affected WKWebView.
  const page = {
    streamTextContent: () => ({ getReader: () => ({ read, releaseLock }) }),
    cleanup: vi.fn(),
  };
  state.getDocument.mockReturnValue({
    promise: Promise.resolve({ numPages: 1, getPage: async () => page }),
    destroy,
  });
  expect(await pdfText("YQ==", () => false)).toEqual({
    pages: [{ page: 1, text: "巴拿赫 问题" }],
    truncated: false,
  });
  expect(releaseLock).toHaveBeenCalledOnce();
  expect(destroy).toHaveBeenCalledOnce();
});
