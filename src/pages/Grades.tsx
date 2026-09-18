import { useState } from "react";
import { CircleHelp } from "lucide-react";
import { useResource, openOfficial, action } from "../lib/api";
import { Button, Empty, Resource, type Login } from "../components/ui";
import {
  calculateGrades,
  officialGpa,
  gradeRulesUrl,
  type Scores,
} from "../lib/grades";

function GradeSummary({ data, semester }: { data: Scores; semester: string }) {
  const [openError, setOpenError] = useState("");
  const courses = data.courses.filter(
    (c) => semester === "all" || `${c.xnd}-${c.xq}` === semester,
  );
  const calculated = calculateGrades(courses);
  const official = officialGpa(
    semester === "all"
      ? data.overall_gpa
      : data.semester_gpas.find((s) => s.xndxq === semester)?.gpa,
  );
  const gpa = official ?? calculated.gpa;
  return (
    <>
      <div className="study-metrics grade-metrics">
        <div>
          <div className="grade-label">
            <span>{semester === "all" ? "累计 GPA" : "学期 GPA"}</span>
            <details
              className="grade-help"
              onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget))
                  e.currentTarget.open = false;
              }}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  e.currentTarget.open = false;
                  e.currentTarget.querySelector("summary")?.focus();
                }
              }}
            >
              <summary aria-label="成绩计算说明">
                <CircleHelp size={14} aria-hidden="true" />
              </summary>
              <div className="grade-help-content">
                <p>
                  GPA 按北大本科规则、平均分按学分加权。当前纳入{" "}
                  {calculated.included} 条成绩，共 {calculated.credits} 学分。
                </p>
                <p>
                  W、IP、合格制等不计入，重修逐次计入。毕业论文、综合性考试依名称和类别排除；以学校成绩单为准。
                </p>
                <Button
                  variant="quiet"
                  onClick={() => {
                    void action({ kind: "openLink", url: gradeRulesUrl }).catch(
                      () => setOpenError("官方规则暂时未能打开"),
                    );
                  }}
                >
                  查看官方规则与公式
                </Button>
                {openError && <p role="alert">{openError}</p>}
              </div>
            </details>
          </div>
          <strong>{gpa?.toFixed(2) ?? "—"}</strong>
          <span>{official !== null ? "学校返回" : "按官方规则计算"}</span>
        </div>
        <div>
          <span>学分加权平均分</span>
          <strong>{calculated.average?.toFixed(2) ?? "—"}</strong>
          <span>本地计算</span>
        </div>
        <div>
          <span>累计已获学分</span>
          <strong>{data.total_credits || "—"}</strong>
          <span>学校返回</span>
        </div>
      </div>
    </>
  );
}

export default function Grades({ login }: { login: Login }) {
  const scores = useResource<Scores>({ kind: "scores" });
  const [semester, setSemester] = useState("all");
  const terms = [
    ...new Set(
      (scores.data?.data?.courses ?? []).map((c) => `${c.xnd}-${c.xq}`),
    ),
  ]
    .sort()
    .reverse();
  return (
    <>
      <header className="page-heading">
        <h1>成绩</h1>
        <Button onClick={() => void openOfficial("portal")}>
          打开校内门户
        </Button>
      </header>
      <Resource
        title="课程成绩"
        heading={
          <select
            aria-label="成绩学期"
            value={semester}
            onChange={(e) => setSemester(e.target.value)}
          >
            <option value="all">全部学期</option>
            {terms.map((t) => (
              <option key={t} value={t}>
                {t.replace(/-(\d)$/, " 学年第$1学期")}
              </option>
            ))}
          </select>
        }
        q={scores}
        login={login}
        service="treehole"
      >
        {(data) => (
          <>
            <GradeSummary data={data} semester={semester} />
            {data.courses.length ? (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>课程</th>
                      {semester === "all" && <th>学期</th>}
                      <th>类别</th>
                      <th className="numeric">学分</th>
                      <th className="numeric">成绩</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.courses
                      .filter(
                        (c) =>
                          semester === "all" || `${c.xnd}-${c.xq}` === semester,
                      )
                      .map((c, i) => (
                        <tr key={i}>
                          <td>
                            <strong>{c.kcmc}</strong>
                          </td>
                          {semester === "all" && (
                            <td className="subtle">
                              {c.xnd} · {c.xq}
                            </td>
                          )}
                          <td className="subtle">{c.kclbmc}</td>
                          <td className="numeric">{c.xf || "—"}</td>
                          <td className="numeric">
                            <strong>{c.xqcj || "未公布"}</strong>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty>学校尚未返回成绩记录</Empty>
            )}
          </>
        )}
      </Resource>
    </>
  );
}
