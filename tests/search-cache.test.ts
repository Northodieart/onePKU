import { describe, expect, it } from "vitest";
import {
  emptySearchSnapshot,
  replaceSearchResource,
  snapshotItems,
} from "../src/lib/searchCache";
import { resourceItems, searchItems } from "../src/lib/search";
const course = { id: "c", name: "数学", current: true };
const material = (id: string) =>
  resourceItems(course, "localMaterials", [
    { id, name: "讲义.pdf", bytes: 10, source: "本机" },
  ])[0];
describe("persistent search revisions", () => {
  it("retains unchanged PDF text and removes deleted or replaced pages", () => {
    const s = emptySearchSnapshot("account-a");
    s.courses = [course];
    const old = material("old");
    replaceSearchResource(s, course.id, "localMaterials", [old]);
    s.files[old.key] = {
      pdf: true,
      items: [
        {
          ...old,
          key: old.key + ":p42",
          body: "巴拿赫问题",
          source: { ...old.source, page: 42 },
        },
      ],
    };
    replaceSearchResource(s, course.id, "localMaterials", [old]);
    expect(searchItems(snapshotItems(s), "巴拿赫")).toHaveLength(1);
    replaceSearchResource(s, course.id, "localMaterials", [material("new")]);
    expect(searchItems(snapshotItems(s), "巴拿赫")).toHaveLength(0);
    expect(s.files).toEqual({});
    replaceSearchResource(s, course.id, "localMaterials", []);
    expect(snapshotItems(s).filter((i) => i.local)).toHaveLength(0);
  });
  it("does not reintroduce duplicate teaching attachments after refreshing metadata", () => {
    const s = emptySearchSnapshot("account-a");
    s.courses = [course];
    const local = {
      ...material("revision"),
      local: { ...material("revision").local!, downloadId: "d" },
    };
    replaceSearchResource(s, course.id, "localMaterials", [local]);
    replaceSearchResource(
      s,
      course.id,
      "content",
      resourceItems(course, "content", [
        {
          id: "section",
          title: "讲义",
          description: "",
          attachments: [{ name: "讲义.pdf", downloadId: "d" }],
        },
      ]),
    );
    expect(snapshotItems(s).filter((i) => i.kind === "material")).toEqual([
      local,
    ]);
  });
});
