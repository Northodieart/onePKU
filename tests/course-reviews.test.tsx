import React from "react";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import CourseReviews from "../src/components/CourseReviews";
import { reviewLinks, reviewQuery } from "../src/lib/reviews";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("strips class and semester suffixes before building a search query", () => {
  expect(reviewQuery("程序设计实习(25-26学年第2学期)")).toBe("程序设计实习");
  expect(reviewQuery("高等数学 (B)（2班）")).toBe("高等数学 (B)");
  expect(reviewQuery("  线性代数（B）  ")).toBe("线性代数（B）");
});

it("prefills only the sites that support a query and encodes the name", () => {
  const links = reviewLinks("计算概论（B）");
  expect(links.map((l) => l.id)).toEqual([
    "pinhaoke",
    "pinzhixiaoyuan",
    "pkuhub",
  ]);
  expect(links[0].url).toBe(
    `https://www.pinhaoke.love/reviews?q=${encodeURIComponent("计算概论（B）")}`,
  );
  expect(links[1].prefills).toBe(false);
  expect(links[1].url).toBe("https://courses.pinzhixiaoyuan.com/");
});

it("opens a review site through the openLink command and reports failures", async () => {
  const bodies: Array<Record<string, unknown>> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url, options) => {
      const body = JSON.parse(options.body);
      bodies.push(body);
      return {
        ok: true,
        json: async () =>
          bodies.length === 1
            ? {
                data: { opened: true },
                error: null,
                warnings: [],
                stale: false,
                generation: "public",
                updatedAt: null,
              }
            : {
                data: null,
                error: { code: "invalid", message: "invalid link" },
                warnings: [],
                stale: false,
                generation: "public",
                updatedAt: null,
              },
      };
    }),
  );
  render(<CourseReviews course="数据结构与算法(A)" />);
  fireEvent.click(screen.getByLabelText("查看课程评价"));
  fireEvent.click(screen.getByRole("button", { name: /拼好课/ }));
  await screen.findByLabelText("查看课程评价");
  expect(bodies[0]).toEqual({
    kind: "openLink",
    url: `https://www.pinhaoke.love/reviews?q=${encodeURIComponent("数据结构与算法(A)")}`,
  });
  fireEvent.click(screen.getByLabelText("查看课程评价"));
  fireEvent.click(screen.getByRole("button", { name: /PKUHUB/ }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "PKUHUB暂时未能打开",
  );
});
