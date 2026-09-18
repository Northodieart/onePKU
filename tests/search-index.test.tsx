import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor, cleanup } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useSearchIndex } from "../src/components/useSearchIndex";
import {
  emptySearchSnapshot,
  replaceSearchResource,
} from "../src/lib/searchCache";
import { resourceItems, searchItems } from "../src/lib/search";
const mocks = vi.hoisted(() => ({
  call: vi.fn(),
  load: vi.fn(),
  save: vi.fn(),
  pdf: vi.fn(),
}));
vi.mock("../src/lib/api", () => ({ call: mocks.call }));
vi.mock("../src/lib/materialPdf", () => ({ pdfText: mocks.pdf }));
vi.mock("../src/lib/searchCache", async (original) => ({
  ...(await original<object>()),
  loadSearchSnapshot: mocks.load,
  saveSearchSnapshot: mocks.save,
}));
const c = { id: "c", name: "数学", current: true };
const m = (id: string) => ({ id, name: "讲义.pdf", bytes: 10, source: "本机" });
const env = (data: unknown, generation = "a") => ({
  data,
  generation,
  error: null,
  stale: false,
});
function saved() {
  const s = emptySearchSnapshot("a");
  s.courses = [c];
  const item = resourceItems(c, "localMaterials", [m("r1")])[0];
  replaceSearchResource(s, c.id, "localMaterials", [item]);
  s.files[item.key] = {
    pdf: true,
    items: [
      {
        ...item,
        key: item.key + ":p42",
        body: "巴拿赫问题",
        source: { ...item.source, page: 42 },
      },
    ],
  };
  return s;
}
function mount(generation = "a", enabled = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderHook(() => useSearchIndex(enabled, generation, false, 0), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  });
}
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
describe("search startup and incremental refresh", () => {
  it("shows saved full-text results while the school API is still pending", async () => {
    mocks.load.mockResolvedValue(saved());
    mocks.call.mockReturnValue(new Promise(() => {}));
    const { result } = mount();
    await waitFor(() =>
      expect(searchItems(result.current.items, "巴拿赫")).toHaveLength(1),
    );
    expect(result.current.busy).toBe(true);
    expect(mocks.pdf).not.toHaveBeenCalled();
  });
  it("preloads before opening and skips PDF reads after restart with unchanged revisions", async () => {
    mocks.load.mockResolvedValue(saved());
    mocks.save.mockResolvedValue(undefined);
    mocks.call.mockImplementation(async (req) =>
      env(
        req.kind === "allCourses"
          ? [c]
          : req.kind === "localMaterials"
            ? [m("r1")]
            : [],
      ),
    );
    const cold = mount("a", false);
    await waitFor(() =>
      expect(cold.result.current.items.some((i) => i.source.page === 42)).toBe(
        true,
      ),
    );
    expect(mocks.call).not.toHaveBeenCalled();
    cold.unmount();
    const warm = mount();
    await waitFor(() => expect(warm.result.current.done).toBe(5));
    expect(
      mocks.call.mock.calls.some(([r]) => r.kind === "readLocalMaterial"),
    ).toBe(false);
    expect(mocks.pdf).not.toHaveBeenCalled();
    await waitFor(() => expect(mocks.save).toHaveBeenCalled());
  });
  it("reparses only a new revision and no longer finds deleted old page text", async () => {
    mocks.load.mockResolvedValue(saved());
    mocks.save.mockResolvedValue(undefined);
    mocks.call.mockImplementation(async (req) =>
      env(
        req.kind === "allCourses"
          ? [c]
          : req.kind === "localMaterials"
            ? [m("r2")]
            : req.kind === "readLocalMaterial"
              ? { mime: "application/pdf", base64: "" }
              : [],
      ),
    );
    mocks.pdf.mockResolvedValue({
      pages: [{ page: 3, text: "全新内容" }],
      truncated: false,
    });
    const { result } = mount();
    await waitFor(() =>
      expect(searchItems(result.current.items, "全新内容")).toHaveLength(1),
    );
    expect(searchItems(result.current.items, "巴拿赫")).toHaveLength(0);
    expect(mocks.pdf).toHaveBeenCalledTimes(1);
    expect(
      mocks.call.mock.calls.filter(
        ([r]) => r.kind === "readLocalMaterial",
      )[0][0].id,
    ).toBe("r2");
  });
  it("requests only the current account scope when hydrating", async () => {
    mocks.load.mockResolvedValue(undefined);
    const { result } = mount("b", false);
    await waitFor(() => expect(mocks.load).toHaveBeenCalledWith("b"));
    expect(result.current.items).toEqual([]);
  });
});
