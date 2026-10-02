import { useState } from "react";
import { ArrowLeft, Play } from "lucide-react";
import ReplayPlayer, { type Replay } from "../components/ReplayPlayer";
import {
  AttachmentRow,
  Button,
  Empty,
  Pager,
  Resource,
  Search,
  type Login,
} from "../components/ui";
import { connectHaoxue, useResource, type Haoxue } from "../lib/api";
import { usePageParams } from "../lib/navigation";

/** 一次课堂记录。好学的课程标识 + 课次标识就是它的身份，也是缓存键。 */
type Lesson = Replay & { room?: string };
type Catalogue = {
  page: number;
  rows: Lesson[];
  hasPrev: boolean;
  hasNext: boolean;
  waiting?: number;
};
type CourseRow = {
  courseId: string;
  name: string;
  teacher: string;
  college: string;
  term: string;
};
type CourseList = {
  page: number;
  rows: CourseRow[];
  hasPrev: boolean;
  hasNext: boolean;
};
type Episodes = {
  courseId: string;
  name: string;
  teacher: string;
  term: string;
  rows: Lesson[];
  waiting: number;
};

function today() {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Shanghai" });
}

function lessonLabel(lesson: Lesson) {
  const meta = [lesson.time, lesson.room].filter(Boolean).join(" · ");
  return meta ? `${lesson.title}（${meta}）` : lesson.title;
}

export default function Classroom({ login }: { login: Login }) {
  const [params, navigate] = usePageParams("课堂实录");
  const status = useResource<Haoxue>({ kind: "haoxueStatus" });
  const connected = status.data?.data?.connected ?? false;
  const mode = params.get("mode") === "date" ? "date" : "course";
  const page = Number(params.get("page")) || 1;
  const search = params.get("search") ?? "";
  const date = params.get("date") || today();
  const course = params.get("course") ?? "";
  const video = params.get("video") ?? "";
  const [draft, setDraft] = useState(search);
  const [draftDate, setDraftDate] = useState(date);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState("");
  const courses = useResource<CourseList>(
    { kind: "haoxueCourses", page, search },
    mode === "course" && !course && connected,
  );
  const days = useResource<Catalogue>(
    { kind: "haoxueByDate", date, page },
    mode === "date" && !course && connected,
  );
  const episodes = useResource<Episodes>(
    { kind: "haoxueEpisodes", course },
    !!course && connected,
  );
  const playing = [
    ...(episodes.data?.data?.rows ?? []),
    ...(days.data?.data?.rows ?? []),
  ].find((row) => row.hash_id === video);

  async function connect() {
    setFailure("");
    setBusy(true);
    try {
      await connectHaoxue();
    } catch (error) {
      setFailure(error instanceof Error ? error.message : "无法打开认证窗口");
    }
    setBusy(false);
    void status.refetch();
  }

  if (!connected && !status.data?.error && !status.isFetching)
    return (
      <>
        <header className="page-heading">
          <h1>课堂实录</h1>
        </header>
        <Empty>
          还没有连接课堂实录。连接后按课程或按日期都能翻到全部课堂回放。
        </Empty>
        <Button
          variant="primary"
          disabled={busy}
          onClick={() => void connect()}
        >
          连接课堂实录
        </Button>
        {failure && (
          <p className="inline-error" role="alert">
            {failure}
          </p>
        )}
      </>
    );

  return (
    <>
      <header className="page-heading">
        {course ? (
          <div>
            <Button
              variant="quiet"
              className="back-link"
              onClick={() => navigate({ course: null, video: null })}
            >
              <ArrowLeft size={17} />
              课堂实录
            </Button>
            <h1>{episodes.data?.data?.name || "课堂回放"}</h1>
          </div>
        ) : (
          <h1>课堂实录</h1>
        )}
      </header>
      {!course && (
        <div className="tabs" aria-label="课堂实录浏览方式">
          <button
            className={mode === "course" ? "active" : ""}
            onClick={() =>
              navigate({ mode: null, page: null, search: null, video: null })
            }
          >
            按课程
          </button>
          <button
            className={mode === "date" ? "active" : ""}
            onClick={() =>
              navigate({ mode: "date", page: null, date: null, video: null })
            }
          >
            按日期
          </button>
        </div>
      )}
      {!course && mode === "course" && (
        <div className="toolbar">
          <Search
            value={draft}
            onChange={setDraft}
            placeholder="按课程名搜索课堂实录"
          />
          <Button
            onClick={() =>
              navigate({
                search: draft.trim() || null,
                page: null,
                video: null,
              })
            }
          >
            搜索
          </Button>
        </div>
      )}
      {!course && mode === "date" && (
        <div className="toolbar">
          <input
            type="date"
            aria-label="课堂日期"
            value={draftDate}
            onChange={(e) => setDraftDate(e.target.value)}
          />
          <Button
            onClick={() =>
              navigate({
                date: /^\d{4}-\d{2}-\d{2}$/.test(draftDate)
                  ? draftDate === today()
                    ? null
                    : draftDate
                  : null,
                page: null,
                video: null,
              })
            }
          >
            查看这一天
          </Button>
        </div>
      )}
      {playing && (
        <ReplayPlayer
          key={`${playing.courseId}:${playing.hash_id}:${playing.time}`}
          course={playing.courseId ?? course}
          video={playing}
          generation={(course ? episodes.data : days.data)?.generation ?? ""}
          close={() => navigate({ video: null })}
        />
      )}
      {!playing && course && (
        <Resource
          title="课堂列表"
          q={episodes}
          login={login}
          className="resource-plain"
          heading={
            <span className="subtle">
              {episodes.data?.data?.rows.length ?? 0} 节可播放回放
            </span>
          }
        >
          {(data) =>
            data.rows.length ? (
              <div className="video-list">
                {data.rows.map((row) => (
                  <AttachmentRow
                    key={row.hash_id}
                    file={{ name: lessonLabel(row) }}
                    icon={<Play size={18} />}
                    request={{
                      kind: "downloadVideo",
                      course: row.courseId ?? data.courseId,
                      video: row.hash_id,
                    }}
                    extra={
                      <Button
                        variant="primary"
                        onClick={() => navigate({ video: row.hash_id })}
                      >
                        播放
                      </Button>
                    }
                  />
                ))}
              </div>
            ) : (
              <Empty>这门课还没有生成可播放的回放。</Empty>
            )
          }
        </Resource>
      )}
      {!playing && !course && mode === "date" && (
        <Resource
          title={`${date} 的课堂`}
          q={days}
          login={login}
          className="resource-plain"
          heading={
            <span className="subtle">
              {days.data?.data?.rows.length ?? 0} 节课堂记录
            </span>
          }
          extra={
            <Pager
              page={page}
              hasNext={days.data?.data?.hasNext ?? false}
              onChange={(next) =>
                navigate({ page: next === 1 ? null : String(next) })
              }
            />
          }
        >
          {(data) =>
            data.rows.length ? (
              <div className="video-list">
                {data.rows.map((row) => (
                  <AttachmentRow
                    key={`${row.courseId}:${row.hash_id}`}
                    file={{
                      name: `${row.course_name ?? ""} · ${lessonLabel(row)}`,
                    }}
                    icon={<Play size={18} />}
                    request={{
                      kind: "downloadVideo",
                      course: row.courseId ?? "",
                      video: row.hash_id,
                    }}
                    extra={
                      <Button
                        variant="primary"
                        onClick={() => navigate({ video: row.hash_id })}
                      >
                        播放
                      </Button>
                    }
                  />
                ))}
              </div>
            ) : (
              <Empty>这一天没有可播放的课堂记录。</Empty>
            )
          }
        </Resource>
      )}
      {!playing && !course && mode === "course" && (
        <Resource
          title="课堂实录课程"
          q={courses}
          login={login}
          className="resource-plain"
          heading={<span className="subtle">与好学原站同一份目录</span>}
          extra={
            <Pager
              page={page}
              hasNext={courses.data?.data?.hasNext ?? false}
              onChange={(next) =>
                navigate({ page: next === 1 ? null : String(next) })
              }
            />
          }
        >
          {(data) =>
            data.rows.length ? (
              <div className="notice-list">
                {data.rows.map((row) => (
                  <button
                    key={row.courseId}
                    onClick={() => navigate({ course: row.courseId })}
                  >
                    <small>{row.term || "课堂实录"}</small>
                    <h3>{row.name}</h3>
                    <p>
                      {[row.teacher, row.college].filter(Boolean).join(" · ")}
                    </p>
                  </button>
                ))}
              </div>
            ) : (
              <Empty>
                {search
                  ? `课堂实录里没有「${search}」这门课。`
                  : "目录是空的。"}
              </Empty>
            )
          }
        </Resource>
      )}
    </>
  );
}
