import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { GraduationCap } from "lucide-react";
import { action, useResource, type Course } from "../lib/api";
import type { Scores } from "../lib/grades";
import {
  computeProgress,
  IGNORE,
  inferProfile,
  loadPlan,
  normalizeCourseName,
  planIndex,
  planIndexMeta,
  sectionChoices,
  type MatchedCourse,
  type Progress,
  type ProgressSection,
} from "../lib/curriculum";
import {
  emptyProfile,
  normalizeProfile,
  saveProfile,
  useProfile,
  type Profile,
} from "../lib/profile";
import ProfileForm from "../components/ProfileForm";
import { Button, Empty, type Login } from "../components/ui";

const viaLabel: Record<NonNullable<MatchedCourse["via"]>, string> = {
  name: "课程名匹配",
  alternative: "可替代课程",
  variant: "同名实验班/变体",
  keyword: "按公共课关键词",
  category: "按成绩类别",
  override: "手动归类",
};
const statusLabel: Record<MatchedCourse["status"], string> = {
  passed: "已获",
  failed: "未通过",
  inProgress: "在修",
  withdrawn: "退课",
  other: "其他",
};

function requirementText(s: ProgressSection) {
  if (s.requirement) return s.requirement;
  if (s.min === undefined) return "";
  const unit = s.unit ?? "学分";
  return s.max !== undefined && s.max !== s.min
    ? `${s.min}～${s.max} ${unit}`
    : `${s.min} ${unit}`;
}
function fmt(n: number) {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}
function gap(s: ProgressSection) {
  if (s.min === undefined || (s.unit && s.unit !== "学分")) return null;
  return Math.max(0, s.min - s.earned - s.inProgress);
}

export default function Curriculum({
  login,
  navigate,
}: {
  login: Login;
  navigate: (page: string) => void;
}) {
  const profileQuery = useProfile();
  const scores = useResource<Scores>({ kind: "scores" });
  const courses = useResource<Course[]>({ kind: "allCourses" });
  const saved = normalizeProfile(profileQuery.data?.data ?? null);
  const scoreRows = scores.data?.data?.courses ?? [];
  const courseRows = courses.data?.data ?? [];
  const inference = useMemo(
    () => inferProfile(scoreRows, courseRows),
    [scoreRows, courseRows],
  );
  const [draft, setDraft] = useState<Profile | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const loaded = profileQuery.data !== undefined;
  const editing = loaded && (saved === null || draft !== null);

  useEffect(() => {
    if (!loaded || saved || draft) return;
    if (scores.isPending || courses.isPending) return;
    const top = inference.candidates[0];
    setDraft({
      ...emptyProfile,
      cohort: inference.cohort,
      planId: top?.id ?? null,
      inferred: Boolean(top),
    });
  }, [loaded, saved, draft, inference, scores.isPending, courses.isPending]);

  async function persist(next: Profile) {
    setSaving(true);
    setError("");
    try {
      await saveProfile(next);
      await profileQuery.refetch();
      setDraft(null);
    } catch {
      setError("资料未能保存，请重试");
    } finally {
      setSaving(false);
    }
  }

  const missingLogin = (
    <div className="curriculum-login-hints">
      {scores.data?.error?.code === "auth" ||
      (!scores.data?.data && !scores.isPending) ? (
        <p>
          成绩来自树洞接口。
          <button className="text-button" onClick={() => login("treehole")}>
            连接树洞
          </button>
          后可以自动推断年级与专业，并计算已获学分。
        </p>
      ) : null}
      {courses.data?.error?.code === "auth" ||
      (!courses.data?.data && !courses.isPending) ? (
        <p>
          在修课程来自教学网。
          <button className="text-button" onClick={() => login("course")}>
            连接教学网
          </button>
          后可以看到本学期在修学分。
        </p>
      ) : null}
    </div>
  );

  return (
    <>
      <header className="page-heading">
        <div>
          <h1>培养方案</h1>
          <p className="page-subtitle">
            按教务部公开的培养方案计算学分完成情况，以学校毕业审查为准。
          </p>
        </div>
        {saved && !editing && (
          <Button variant="quiet" onClick={() => setDraft({ ...saved })}>
            修改年级与专业
          </Button>
        )}
      </header>
      {!loaded ? (
        <div className="skeleton" aria-label="正在读取资料">
          <i />
          <i />
        </div>
      ) : editing ? (
        <section className="resource settings-section onboarding">
          <h2>{saved ? "修改年级与专业" : "先确认年级与专业"}</h2>
          {!saved && (
            <p className="section-description">
              只需确认三项信息，之后随时可以在设置里修改。资料只保存在这台电脑上。
            </p>
          )}
          {inference.evidence.length > 0 && (
            <ul className="inference-evidence">
              {inference.evidence.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          )}
          {missingLogin}
          {draft ? (
            <ProfileForm
              value={draft}
              onChange={setDraft}
              inference={inference}
            />
          ) : (
            <div className="skeleton" aria-label="正在推断年级与专业">
              <i />
            </div>
          )}
          <div className="submission-actions">
            <Button
              variant="primary"
              disabled={saving || !draft || !draft.planId}
              onClick={() => draft && void persist(draft)}
            >
              保存
            </Button>
            {saved ? (
              <Button variant="quiet" onClick={() => setDraft(null)}>
                取消
              </Button>
            ) : (
              <Button
                variant="quiet"
                disabled={saving || !draft}
                onClick={() =>
                  draft && void persist({ ...draft, planId: null })
                }
              >
                稍后再说
              </Button>
            )}
            {draft && !draft.planId && (
              <span className="subtle">选择专业后才能计算完成情况</span>
            )}
          </div>
          {error && <p role="alert">{error}</p>}
        </section>
      ) : saved && !saved.planId ? (
        <Empty icon={<GraduationCap />}>
          尚未选择培养方案。
          <button
            className="text-button"
            onClick={() => setDraft({ ...saved })}
          >
            选择年级与专业
          </button>
        </Empty>
      ) : saved ? (
        <>
          {missingLogin}
          <PlanProgress
            planId={saved.planId!}
            profile={saved}
            scoreRows={scoreRows}
            courseRows={courseRows}
            onOverride={(key, sectionId) => {
              const overrides = { ...saved.overrides };
              if (sectionId) overrides[key] = sectionId;
              else delete overrides[key];
              void persist({ ...saved, overrides });
            }}
          />
          {saved.secondaryPlanId && (
            <PlanProgress
              planId={saved.secondaryPlanId}
              profile={saved}
              scoreRows={scoreRows}
              courseRows={courseRows}
              secondary
              onOverride={(key, sectionId) => {
                const overrides = { ...saved.overrides };
                if (sectionId)
                  overrides[`${saved.secondaryPlanId}:${key}`] = sectionId;
                else delete overrides[`${saved.secondaryPlanId}:${key}`];
                void persist({ ...saved, overrides });
              }}
            />
          )}
          <p className="subtle curriculum-footnote">
            数据来自教务部公开 PDF（索引更新于 {planIndexMeta.generatedAt}
            ），由社区整理，可能有误。
            <button
              className="text-button"
              onClick={() =>
                void action({ kind: "openLink", url: planIndexMeta.source })
              }
            >
              教务部培养方案页
            </button>
            ·
            <button className="text-button" onClick={() => navigate("设置")}>
              在设置中修改资料
            </button>
          </p>
        </>
      ) : null}
    </>
  );
}

function PlanProgress({
  planId,
  profile,
  scoreRows,
  courseRows,
  secondary = false,
  onOverride,
}: {
  planId: string;
  profile: Profile;
  scoreRows: Scores["courses"];
  courseRows: Course[];
  secondary?: boolean;
  onOverride: (key: string, sectionId: string | null) => void;
}) {
  const plan = useQuery({
    queryKey: ["plan", planId],
    queryFn: () => loadPlan(planId),
    staleTime: Infinity,
  });
  const entry = planIndex.find((p) => p.id === planId);
  const overrides = useMemo(() => {
    if (!secondary) {
      return Object.fromEntries(
        Object.entries(profile.overrides).filter(([k]) => !k.includes(":")),
      );
    }
    const prefix = `${planId}:`;
    return Object.fromEntries(
      Object.entries(profile.overrides)
        .filter(([k]) => k.startsWith(prefix))
        .map(([k, v]) => [k.slice(prefix.length), v]),
    );
  }, [profile.overrides, planId, secondary]);
  const progress = useMemo(
    () =>
      plan.data
        ? computeProgress(plan.data, scoreRows, courseRows, overrides)
        : null,
    [plan.data, scoreRows, courseRows, overrides],
  );
  if (plan.isPending)
    return (
      <div className="skeleton" aria-label="正在读取培养方案">
        <i />
        <i />
      </div>
    );
  if (plan.error || !progress)
    return (
      <section className="resource">
        <p role="alert">
          培养方案“{entry?.title ?? planId}”未能读取，请重新选择专业。
        </p>
      </section>
    );
  return (
    <ProgressView
      progress={progress}
      secondary={secondary}
      onOverride={onOverride}
    />
  );
}

function ProgressView({
  progress,
  secondary,
  onOverride,
}: {
  progress: Progress;
  secondary: boolean;
  onOverride: (key: string, sectionId: string | null) => void;
}) {
  const { plan, sections, pending, ignored, totals } = progress;
  const choices = sectionChoices(progress);
  const inferredTitle = plan.titleInference;
  return (
    <section
      className={`resource curriculum-plan ${secondary ? "secondary" : ""}`}
      aria-label={`${secondary ? "双学位方案" : "主修方案"}：${plan.title}`}
    >
      <div className="resource-heading">
        <div>
          <h2>
            {secondary ? "双学位 / 辅修：" : ""}
            {plan.school} · {plan.title}
          </h2>
          <p className="subtle">
            {plan.volume}
            {plan.degree ? ` · ${plan.degree}` : ""}
            {inferredTitle
              ? ` · 专业名按 ${inferredTitle.from} 版推断，请核对`
              : ""}
            {plan.warnings.length
              ? ` · 该方案有 ${plan.warnings.length} 处解析警告`
              : ""}
          </p>
        </div>
      </div>
      <div className="study-metrics grade-metrics">
        <div>
          <span>已获学分</span>
          <strong>{fmt(totals.earned)}</strong>
          <span>按已通过成绩汇总</span>
        </div>
        <div>
          <span>在修学分</span>
          <strong>{fmt(totals.inProgress)}</strong>
          <span>本学期与未出分课程</span>
        </div>
        <div>
          <span>毕业总学分</span>
          <strong>{totals.required ?? "—"}</strong>
          <span>
            {totals.required
              ? `还差 ${fmt(Math.max(0, totals.required - totals.earned - totals.inProgress))}`
              : "方案未标注"}
          </span>
        </div>
        {pending.length > 0 && (
          <div>
            <span>待确认</span>
            <strong>{pending.length}</strong>
            <span>门课未能归类</span>
          </div>
        )}
      </div>
      {totals.unknownCredits > 0 && (
        <p className="subtle">
          有 {totals.unknownCredits} 门课的学分未知，未计入合计。
        </p>
      )}
      <div className="table-scroll">
        <table className="curriculum-table">
          <thead>
            <tr>
              <th>学分系列</th>
              <th>要求</th>
              <th className="numeric">已获</th>
              <th className="numeric">在修</th>
              <th className="numeric">缺口</th>
            </tr>
          </thead>
          <tbody>
            {sections.map((top) => (
              <SectionRows key={top.id} section={top} depth={0} />
            ))}
          </tbody>
        </table>
      </div>
      {pending.length > 0 && (
        <div className="curriculum-pending">
          <h3>待确认（{pending.length}）</h3>
          <p className="subtle">
            这些课在方案课程表里没有同名条目，也无法按类别判断。归类只保存在本机，可随时改。
          </p>
          <ul>
            {pending.map((c) => (
              <li key={c.key}>
                <div className="grow">
                  <strong>{c.name}</strong>
                  <span className="subtle">
                    {c.term} · {c.category} · {statusLabel[c.status]}
                    {c.credits !== null ? ` · ${fmt(c.credits)} 学分` : ""}
                  </span>
                </div>
                <select
                  aria-label={`归类 ${c.name}`}
                  value=""
                  onChange={(e) =>
                    e.target.value &&
                    onOverride(normalizeCourseName(c.name), e.target.value)
                  }
                >
                  <option value="">归入…</option>
                  {choices.map((ch) => (
                    <option key={ch.id} value={ch.id}>
                      {ch.label}
                    </option>
                  ))}
                  <option value={IGNORE}>不计入</option>
                </select>
              </li>
            ))}
          </ul>
        </div>
      )}
      {ignored.length > 0 && (
        <details className="settings-explanation">
          <summary>不计入的记录（{ignored.length}）</summary>
          <ul className="curriculum-ignored">
            {ignored.map((c) => (
              <li key={c.key}>
                <span>
                  {c.name} · {c.term} · {statusLabel[c.status]}
                  {c.score ? ` · ${c.score}` : ""}
                </span>
                {c.via === "override" && (
                  <button
                    className="text-button"
                    onClick={() =>
                      onOverride(normalizeCourseName(c.name), null)
                    }
                  >
                    恢复
                  </button>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}
      {(plan.notes.length > 0 || plan.warnings.length > 0) && (
        <details className="settings-explanation">
          <summary>方案原文备注与数据说明</summary>
          {plan.notes.length > 0 && (
            <ul className="curriculum-notes">
              {plan.notes.slice(0, 40).map((n, i) => (
                <li key={i}>{n}</li>
              ))}
            </ul>
          )}
          {plan.warnings.length > 0 && (
            <>
              <p className="subtle">解析警告（可在仓库 overrides 中修正）：</p>
              <ul className="curriculum-notes">
                {plan.warnings.slice(0, 20).map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            </>
          )}
          <button
            className="text-button"
            onClick={() =>
              void action({ kind: "openLink", url: plan.source.url })
            }
          >
            下载原文 PDF（{plan.volume}）
          </button>
        </details>
      )}
    </section>
  );
}

function SectionRows({
  section,
  depth,
}: {
  section: ProgressSection;
  depth: number;
}) {
  const [open, setOpen] = useState(false);
  const g = gap(section);
  const hasCourses = section.courses.length > 0;
  return (
    <>
      <tr
        className={`depth-${depth} ${g === 0 && section.min !== undefined ? "complete" : ""}`}
      >
        <td>
          {hasCourses ? (
            <button
              className="text-button section-toggle"
              aria-expanded={open}
              onClick={() => setOpen(!open)}
            >
              {open ? "▾" : "▸"} {section.name}
              <span className="subtle"> {section.courses.length} 门</span>
            </button>
          ) : (
            <span className="section-name">{section.name}</span>
          )}
          {section.note && <span className="subtle"> · {section.note}</span>}
        </td>
        <td className="subtle">{requirementText(section)}</td>
        <td className="numeric">
          {section.unit === "门" ? section.passedCount : fmt(section.earned)}
        </td>
        <td className="numeric">
          {section.inProgress ? fmt(section.inProgress) : "—"}
        </td>
        <td className="numeric">
          {section.unit && section.unit !== "学分"
            ? "—"
            : g === null
              ? "—"
              : g === 0
                ? "已满足"
                : fmt(g)}
        </td>
      </tr>
      {open &&
        section.courses.map((c) => (
          <tr key={c.key} className="course-row">
            <td colSpan={2}>
              <span className="course-indent">{c.name}</span>
              <span className="subtle">
                {" "}
                · {c.term} · {c.via ? viaLabel[c.via] : ""}
              </span>
            </td>
            <td className="numeric">
              {c.status === "passed"
                ? c.credits !== null
                  ? fmt(c.credits)
                  : "?"
                : "—"}
            </td>
            <td className="numeric">
              {c.status === "inProgress"
                ? c.credits !== null
                  ? fmt(c.credits)
                  : "?"
                : "—"}
            </td>
            <td className="subtle">
              {statusLabel[c.status]}
              {c.score && c.status !== "inProgress" ? ` ${c.score}` : ""}
            </td>
          </tr>
        ))}
      {section.children.map((child) => (
        <SectionRows key={child.id} section={child} depth={depth + 1} />
      ))}
    </>
  );
}
