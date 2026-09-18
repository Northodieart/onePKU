import React from "react";
import { afterEach, it, expect, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  cleanup,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import Submission from "../src/components/Submission";
import { chooseAssignmentFile, type Assignment } from "../src/lib/api";
vi.mock("../src/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/lib/api")>()),
  chooseAssignmentFile: vi.fn(),
}));
const fixture: Assignment = {
  hash_id: "fixture",
  course_name: "示例课程",
  course_id: "_1_1",
  content_id: "_2_1",
  title: "示例作业",
  deadline_raw: null,
  deadline: null,
  last_attempt: null,
  detail_error: false,
  descriptions: [],
  attachments: [],
};
const file = { id: "file", name: "answer.txt", bytes: 6, sha256: "hash" };
const operation = {
  id: "operation",
  course: "_1_1",
  content: "_2_1",
  title: "示例作业",
  courseName: "示例课程",
  file,
  state: "prepared",
  message: "请核对",
  created: 1,
  receipt: null,
};
function mount() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <Submission assignment={fixture} />
    </QueryClientProvider>,
  );
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});
function mockApi() {
  const fn = vi.fn(async (_u, o) => {
    const r = JSON.parse(o.body);
    return {
      ok: true,
      json: async () => ({
        data:
          r.kind === "prepareSubmission"
            ? operation
            : r.kind === "commitSubmission"
              ? { ...operation, state: "unknown", message: "结果待确认" }
              : {},
        error: null,
        warnings: [],
        generation: "test",
        updatedAt: null,
        stale: false,
      }),
    };
  });
  vi.stubGlobal("fetch", fn);
  vi.mocked(chooseAssignmentFile).mockResolvedValue(file);
  return fn;
}
async function review() {
  fireEvent.click(
    screen.getByRole("button", { name: "提交作业", exact: true }),
  );
  fireEvent.click(screen.getByRole("button", { name: "选择附件" }));
  await screen.findByText("answer.txt");
  fireEvent.click(screen.getByRole("button", { name: "核对提交内容" }));
  await screen.findByRole("heading", { name: "确认这份作业" });
}
it("file selection and review do not send; the final button sends exactly once", async () => {
  const fetch = mockApi();
  mount();
  await review();
  expect(fetch.mock.calls.map(([, o]) => JSON.parse(o.body).kind)).toEqual([
    "prepareSubmission",
  ]);
  const final = screen.getByRole("button", { name: "确认提交到教学网" });
  fireEvent.click(final);
  fireEvent.click(final);
  await screen.findByText("结果待确认");
  expect(
    fetch.mock.calls.filter(
      ([, o]) => JSON.parse(o.body).kind === "commitSubmission",
    ),
  ).toHaveLength(1);
  expect(
    screen.queryByRole("button", { name: "确认提交到教学网" }),
  ).not.toBeInTheDocument();
});
it("closing a reviewed draft cancels locally and never commits", async () => {
  const fetch = mockApi();
  const view = mount();
  await review();
  view.unmount();
  await waitFor(() =>
    expect(
      fetch.mock.calls.some(
        ([, o]) => JSON.parse(o.body).kind === "endSubmission",
      ),
    ).toBe(true),
  );
  expect(
    fetch.mock.calls.some(
      ([, o]) => JSON.parse(o.body).kind === "commitSubmission",
    ),
  ).toBe(false);
});
it("a file picker cancelled by the user sends no request", async () => {
  const fetch = mockApi();
  vi.mocked(chooseAssignmentFile).mockResolvedValue(null);
  mount();
  fireEvent.click(
    screen.getByRole("button", { name: "提交作业", exact: true }),
  );
  fireEvent.click(screen.getByRole("button", { name: "选择附件" }));
  await waitFor(() => expect(chooseAssignmentFile).toHaveBeenCalledOnce());
  expect(fetch).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "核对提交内容" })).toBeDisabled();
});
it("does not offer file submission when the teacher explicitly says no file is required", () => {
  mockApi();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Submission
        assignment={{
          ...fixture,
          descriptions: ["本次作业无需提交任何文件。"],
        }}
      />
    </QueryClientProvider>,
  );
  expect(
    screen.getByRole("button", { name: "在教学网查看" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "提交作业", exact: true }),
  ).not.toBeInTheDocument();
});
