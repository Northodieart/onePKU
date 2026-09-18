import type { Assignment, Content, Course, Notice, Attachment } from "./api";
import type { Material } from "../components/LocalMaterials";
import type { Replay } from "../components/ReplayPlayer";
import { pageLink } from "./navigation";

export type SearchKind =
  "course" | "material" | "replay" | "assignment" | "notice";
export const searchLabels: Record<SearchKind, string> = {
  course: "课程",
  material: "资料",
  replay: "回放",
  assignment: "作业",
  notice: "通知",
};
// IDs identify original resources, not result positions. A PDF page keeps its file revision ID.
export type SearchSource = {
  course: string;
  kind: SearchKind;
  id: string;
  page?: number;
};
export type SearchItem = {
  key: string;
  title: string;
  body: string;
  course: Course;
  kind: SearchKind;
  source: SearchSource;
  local?: Material;
  attachment?: Attachment;
  replay?: Replay;
  url?: string;
  route: string;
  detail?: string;
};
export function courseItem(course: Course): SearchItem {
  return {
    key: `course:${course.id}`,
    title: course.name,
    body: course.semester ?? "",
    course,
    kind: "course",
    source: { course: course.id, kind: "course", id: course.id },
    route: pageLink("课程", { course: course.id }),
  };
}
export function resourceItems(
  course: Course,
  kind: string,
  values: unknown[],
): SearchItem[] {
  const base = (
    id: string,
    type: SearchKind,
    title: string,
    body: string,
    tab: string,
  ): SearchItem => ({
    key: `${course.id}:${type}:${id}`,
    title,
    body,
    course,
    kind: type,
    source: { course: course.id, kind: type, id },
    route: pageLink("课程", { course: course.id, tab }),
  });
  if (kind === "localMaterials")
    return (values as Material[]).map((m) => ({
      ...base(m.id, "material", m.name, "", "materials"),
      local: m,
      detail: m.source,
    }));
  if (kind === "content")
    return (values as Content[]).flatMap((c) => [
      ...(c.description || !c.attachments.length
        ? [base(c.id, "material", c.title, c.description, "materials")]
        : []),
      ...c.attachments
        .filter((a) => a.name.trim())
        .map((a, i) => ({
          ...base(
            `${c.id}:${i}`,
            "material",
            a.name,
            `${c.title}\n${c.description}`,
            "materials",
          ),
          attachment: a,
          detail: "教学网附件",
        })),
    ]);
  if (kind === "videos")
    return (values as Replay[]).map((v) => ({
      ...base(v.hash_id, "replay", v.title, v.time, "videos"),
      replay: v,
      route: pageLink("课程", {
        course: course.id,
        tab: "videos",
        video: v.hash_id,
      }),
    }));
  if (kind === "courseNotices")
    return (values as Notice[]).map((n, i) => ({
      ...base(
        n.announcement.id ?? `${i}`,
        "notice",
        n.announcement.title,
        n.announcement.body,
        "notices",
      ),
      url: n.announcement.url,
      detail: `${n.announcement.date} · ${n.announcement.author}`,
    }));
  if (kind === "courseAssignments")
    return (values as Assignment[]).map((a) => ({
      ...base(
        a.hash_id,
        "assignment",
        a.title,
        a.descriptions.join("\n\n"),
        "assignments",
      ),
      route: pageLink("作业", { course: course.id, assignment: a.hash_id }),
      detail: a.deadline_raw ?? "",
    }));
  return [];
}
const normalizedItems = new WeakMap<
  SearchItem,
  { title: string; course: string; body: string; haystack: string }
>();
const titleOrder = new Intl.Collator("zh-CN", { numeric: true });
function normalized(item: SearchItem) {
  let value = normalizedItems.get(item);
  if (!value) {
    const title = item.title.toLocaleLowerCase();
    const course = item.course.name.toLocaleLowerCase();
    const body = item.body.toLocaleLowerCase();
    value = { title, course, body, haystack: `${title} ${course} ${body}` };
    normalizedItems.set(item, value);
  }
  return value;
}
export function searchItems(
  items: SearchItem[],
  query: string,
  kind = "",
  course = "",
) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return items
    .flatMap((item) => {
      if ((kind && item.kind !== kind) || (course && item.course.id !== course))
        return [];
      const { title, course: courseName, body, haystack } = normalized(item);
      if (!terms.every((t) => haystack.includes(t))) return [];
      if (
        item.source.page &&
        terms.length &&
        !terms.some((t) => body.includes(t))
      )
        return [];
      const score = terms.reduce(
        (n, t) =>
          n +
          (title === t
            ? 100
            : title.includes(t)
              ? 20
              : courseName.includes(t)
                ? 8
                : 1),
        0,
      );
      return [{ item, score }];
    })
    .sort(
      (a, b) =>
        b.score - a.score ||
        Number(!!b.item.course.current) - Number(!!a.item.course.current) ||
        titleOrder.compare(a.item.title, b.item.title),
    )
    .map((r) => r.item);
}
export function excerpt(body: string, query: string) {
  const term = query
    .trim()
    .toLocaleLowerCase()
    .split(/\s+/)
    .find((t) => body.toLocaleLowerCase().includes(t));
  const index = term ? body.toLocaleLowerCase().indexOf(term) : 0;
  const start = Math.max(0, index - 44);
  return `${start ? "…" : ""}${body.slice(start, start + 180).replace(/\s+/g, " ")}${body.length > start + 180 ? "…" : ""}`;
}
