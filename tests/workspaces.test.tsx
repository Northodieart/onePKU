import React from "react";
import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AssignmentWorkspace } from "../src/pages/Assignments";
import Bookings from "../src/pages/Bookings";
import { AttachmentRow } from "../src/components/ui";
const env = (data: unknown) => ({
  data,
  updatedAt: new Date().toISOString(),
  generation: "test",
  error: null,
  warnings: [],
  stale: false,
});
function mount(node: React.ReactNode) {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      {node}
    </QueryClientProvider>,
  );
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("keeps missing submission data out of the pending-work filter", async () => {
  const base = {
    course_name: "测试课",
    course_id: "_1_1",
    content_id: "_2_1",
    deadline: null,
    deadline_raw: null,
    last_attempt: null,
    attachments: [],
    descriptions: [],
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: true,
      json: async () =>
        env([
          { ...base, hash_id: "a", title: "待处理作业", detail_error: false },
          { ...base, hash_id: "b", title: "暂未获取详情", detail_error: true },
        ]),
    })),
  );
  mount(<AssignmentWorkspace login={() => {}} />);
  await screen.findByRole("button", { name: /待处理作业/ });
  fireEvent.change(screen.getByRole("combobox", { name: "作业状态" }), {
    target: { value: "pending" },
  });
  expect(
    screen.queryByRole("button", { name: /暂未获取详情/ }),
  ).not.toBeInTheDocument();
  fireEvent.change(screen.getByRole("combobox", { name: "作业状态" }), {
    target: { value: "unknown" },
  });
  expect(
    screen.getByRole("button", { name: /暂未获取详情/ }),
  ).toBeInTheDocument();
});
it("requests the reservation account without affecting teaching login", async () => {
  const login = vi.fn();
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: true,
      json: async () => ({
        ...env(null),
        error: { code: "auth", message: "请连接北大空间" },
      }),
    })),
  );
  mount(<Bookings login={login} />);
  fireEvent.click(
    screen.getByRole("button", { name: "教学研讨", exact: true }),
  );
  fireEvent.click(
    await screen.findByRole("button", { name: "重新登录", exact: true }),
  );
  expect(login.mock.calls[0][0]).toBe("bdkj");
  expect(
    screen.getByRole("button", { name: "体育场馆", exact: true }),
  ).toBeEnabled();
});
it("shows the profile prerequisite without asking for another login", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: true,
      json: async () => ({
        ...env(null),
        error: {
          code: "bookingProfile",
          message: "学校要求先完善联系电话和联系邮箱，完成后即可重新查询时段",
        },
      }),
    })),
  );
  mount(<Bookings login={() => {}} />);
  expect(
    await screen.findByRole("button", { name: "完善预约资料" }),
  ).toBeEnabled();
  expect(
    screen.queryByRole("button", { name: "重新登录" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByRole("table")).not.toBeInTheDocument();
});
