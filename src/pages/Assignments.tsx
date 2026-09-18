import { useState, type ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import {
  useResource,
  openOfficial,
  fmtTime,
  type Assignment,
  type Course,
} from "../lib/api";
import { Button, Empty, Resource, Search, type Login } from "../components/ui";
import { AssignmentDetails } from "../components/AssignmentDetails";
import { usePageParams } from "../lib/navigation";

export function assignmentStatus(a: Assignment, now = Date.now()) {
  if (a.detail_error) return "unknown";
  if (a.last_attempt) return "submitted";
  if (a.deadline && Date.parse(a.deadline) < now) return "overdue";
  return "pending";
}
export function AssignmentWorkspace({
  login,
  course,
  selected,
  onSelect,
  courseFilter,
}: {
  login: Login;
  course?: string;
  selected?: string;
  onSelect?: (id?: string) => void;
  courseFilter?: ReactNode;
}) {
  const q = useResource<Assignment[]>(
    course ? { kind: "courseAssignments", course } : { kind: "assignments" },
  );
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("all");
  const [localSelected, setLocalSelected] = useState<string>();
  const activeId = onSelect ? selected : localSelected;
  const select = onSelect ?? setLocalSelected;
  const data = q.data?.data ?? [];
  const rows = data
    .filter(
      (a) =>
        `${a.title} ${a.course_name}`
          .toLocaleLowerCase()
          .includes(search.toLocaleLowerCase()) &&
        (filter === "all" || assignmentStatus(a) === filter),
    )
    .sort(
      (a, b) =>
        (a.deadline ? Date.parse(a.deadline) : Infinity) -
          (b.deadline ? Date.parse(b.deadline) : Infinity) ||
        a.title.localeCompare(b.title, "zh-CN"),
    );
  if (activeId)
    return (
      <section className="assignment-full">
        <Button
          variant="quiet"
          className="back-link"
          onClick={() => select(undefined)}
        >
          <ArrowLeft size={17} />
          作业列表
        </Button>
        <Resource
          title="作业详情"
          q={q}
          service="course"
          login={login}
          className="resource-page"
        >
          {(assignments) => {
            const active = assignments.find((a) => a.hash_id === activeId);
            return active ? (
              <AssignmentDetails
                key={active.hash_id}
                assignment={active}
                login={login}
              />
            ) : (
              <Empty>当前列表未找到这项作业，请返回列表刷新。</Empty>
            );
          }}
        </Resource>
      </section>
    );
  return (
    <>
      <div className="assignment-filters">
        <Search
          value={search}
          onChange={setSearch}
          placeholder="搜索作业或课程"
        />
        {courseFilter}
        <select
          aria-label="作业状态"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        >
          <option value="all">全部状态</option>
          <option value="pending">待处理</option>
          <option value="overdue">已截止未提交</option>
          <option value="submitted">有提交记录</option>
          <option value="unknown">状态待核对</option>
        </select>
      </div>
      <Resource
        title="作业列表"
        q={q}
        service="course"
        login={login}
        className="resource-plain"
        heading={<span className="subtle">{rows.length} 项作业</span>}
      >
        {() =>
          rows.length ? (
            <div className="assignment-index">
              {rows.map((a) => {
                const state = assignmentStatus(a);
                return (
                  <button
                    key={a.hash_id}
                    className="assignment-choice"
                    onClick={() => select(a.hash_id)}
                  >
                    <strong>{a.title}</strong>
                    <small>{a.course_name}</small>
                    <span
                      className={state === "overdue" ? "overdue" : "subtle"}
                    >
                      {state === "unknown"
                        ? "状态待核对"
                        : state === "submitted"
                          ? "有提交记录"
                          : a.deadline
                            ? `${state === "overdue" ? "已截止 · " : "截止 "}${fmtTime(a.deadline)}`
                            : null}
                    </span>
                  </button>
                );
              })}
            </div>
          ) : (
            <Empty>
              {data.length ? "没有符合筛选条件的作业" : "本次查询未列出作业"}
            </Empty>
          )
        }
      </Resource>
    </>
  );
}
export default function Assignments({ login }: { login: Login }) {
  const courses = useResource<Course[]>({ kind: "allCourses" });
  const [params, navigate] = usePageParams("作业");
  const course = params.get("course") ?? "";
  const selected = params.get("assignment") ?? undefined;
  return (
    <>
      {!selected && (
        <>
          <header className="page-heading">
            <h1>作业</h1>
            <Button onClick={() => void openOfficial("course")}>
              打开教学网
            </Button>
          </header>
        </>
      )}
      <AssignmentWorkspace
        key={course}
        courseFilter={
          <select
            aria-label="作业课程"
            value={course}
            onChange={(e) =>
              navigate({ course: e.target.value, assignment: null })
            }
          >
            <option value="">本学期全部课程</option>
            {(courses.data?.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} · {c.semester}
              </option>
            ))}
          </select>
        }
        course={course || undefined}
        login={login}
        selected={selected}
        onSelect={(id) => navigate({ assignment: id ?? null })}
      />
    </>
  );
}
