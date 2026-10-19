import React from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import Downloads from "../src/components/Downloads";
import { action, useResource } from "../src/lib/api";

vi.mock("../src/lib/api", () => ({ action: vi.fn(), useResource: vi.fn() }));
beforeEach(() => {
  // 默认当作「设置里的保存位置还没读到」，各用例再自己覆盖。
  vi.mocked(useResource).mockReturnValue({
    data: null,
    error: null,
  } as never);
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it("retries a failed download from its record and preserves the visible segment count", async () => {
  let retried = false;
  vi.mocked(action).mockImplementation(async (request: any) => {
    if (request.kind === "downloadRetry") {
      retried = true;
      return { id: "new-job" };
    }
    return [
      {
        id: "failed-job",
        name: "ICS",
        state: "failed",
        completed: 219,
        segments: 719,
        message: "视频片段下载超时",
      },
      ...(retried ? [{ id: "new-job", name: "ICS", state: "queued" }] : []),
    ];
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Downloads />
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByTitle("下载记录"));
  expect(await screen.findByText(/219 \/ 719/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "重试下载 ICS" }));
  await waitFor(() =>
    expect(action).toHaveBeenCalledWith({
      kind: "downloadRetry",
      id: "failed-job",
    }),
  );
  expect(await screen.findByText("等待下载")).toBeInTheDocument();
  client.clear();
});

it("shows authentication failures from retry without discarding the failed record", async () => {
  vi.mocked(action).mockImplementation(async (request: any) => {
    if (request.kind === "downloadRetry")
      throw new Error("登录已失效，请重新登录");
    return [{ id: "job", name: "ICS", state: "failed", message: "下载未完成" }];
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Downloads />
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByTitle("下载记录"));
  fireEvent.click(await screen.findByRole("button", { name: "重试下载 ICS" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("登录已失效");
  expect(screen.getByText("下载未完成")).toBeInTheDocument();
  client.clear();
});

it("names the save folder taken from settings and opens it on demand", async () => {
  vi.mocked(useResource).mockReturnValue({
    data: {
      data: {
        keepAlive: true,
        downloadRoot: "D:/课件/OnePKU",
        downloadRootIsDefault: false,
        cacheRoot: null,
        cacheRootIsDefault: true,
      },
      error: null,
    },
    error: null,
  } as never);
  vi.mocked(action).mockImplementation(async (request: any) =>
    request.kind === "openDownloadRoot"
      ? { opened: true }
      : [
          {
            id: "job",
            name: "ICS",
            state: "done",
            path: "D:/课件/OnePKU/25-26-1/可视计算/lecture-01.mp4",
          },
        ],
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Downloads />
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByTitle("下载记录"));
  // 提示要跟着设置里选的目录走，不能一直写着「下载 / OnePKU」。
  expect(
    await screen.findByText(/^文件保存在「D:\/课件\/OnePKU」/),
  ).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "打开文件夹" }));
  await waitFor(() =>
    expect(action).toHaveBeenCalledWith({ kind: "openDownloadRoot" }),
  );
  client.clear();
});

it("reports an unopenable save folder without dropping the records", async () => {
  vi.mocked(action).mockImplementation(async (request: any) => {
    if (request.kind === "openDownloadRoot") throw new Error("驱动器已断开");
    return [{ id: "job", name: "ICS", state: "done", path: "/tmp/lec.mp4" }];
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Downloads />
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByTitle("下载记录"));
  // 读不到设置时不编一个路径出来。
  expect(
    await screen.findByText(/^文件按设置里选的保存位置归档/),
  ).toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "打开文件夹" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "未能打开保存位置：驱动器已断开",
  );
  expect(screen.getByText("已保存：/tmp/lec.mp4")).toBeInTheDocument();
  client.clear();
});
