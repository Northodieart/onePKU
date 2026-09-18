import { describe, it, expect } from "vitest";
import {
  courseItem,
  resourceItems,
  searchItems,
  excerpt,
} from "../src/lib/search";
const c = {
  id: "course-1",
  name: "计算机系统导论",
  current: true,
  semester: "26-27",
};
describe("learning search source and relevance", () => {
  it("finds a phrase in a PDF page and preserves revision and page instead of array position", () => {
    const base = resourceItems(c, "localMaterials", [
      { id: "revision-a", name: "intro.pdf", bytes: 10, source: "本机" },
    ])[0];
    const page = {
      ...base,
      key: base.key + ":p7",
      body: "Programs are translated by other programs into different forms",
      source: { ...base.source, page: 7 },
    };
    const hit = searchItems([base, page], "translated different")[0];
    expect(hit.source).toEqual({
      course: c.id,
      kind: "material",
      id: "revision-a",
      page: 7,
    });
    expect(searchItems([base, page], "translated absent")).toHaveLength(0);
  });
  it("ranks exact titles first, scopes course and kind, and never merges equal names from different courses", () => {
    const items = [
      courseItem(c),
      courseItem({ ...c, id: "course-2" }),
      ...resourceItems(c, "content", [
        {
          id: "chapter",
          title: "Introduction",
          description: "计算机系统导论 course introduction",
          attachments: [],
        },
      ]),
    ];
    expect(searchItems(items, "计算机系统导论")[0].kind).toBe("course");
    expect(searchItems(items, "计算机系统导论", "course")).toHaveLength(2);
    expect(searchItems(items, "计算机系统导论", "material", c.id)).toHaveLength(
      1,
    );
  });
  it("opens the exact replay and assignment, including reserved URL characters", () => {
    const replay = resourceItems(c, "videos", [
      { hash_id: "v&2", title: "第一节", time: "2026-09-07", url: "" },
    ])[0];
    expect(replay.route).toBe("课程?course=course-1&tab=videos&video=v%262");
    const assignment = resourceItems(c, "courseAssignments", [
      {
        hash_id: "a?3",
        title: "作业",
        descriptions: ["真实说明"],
        deadline_raw: null,
      },
    ])[0];
    expect(assignment.route).toBe("作业?course=course-1&assignment=a%3F3");
  });
  it("centers a bounded excerpt on a later match", () => {
    const snippet = excerpt(
      "a".repeat(300) + "目标短语" + "b".repeat(300),
      "目标",
    );
    expect(snippet).toContain("目标短语");
    expect(snippet.length).toBeLessThan(185);
    expect(snippet.startsWith("…")).toBe(true);
  });
});
