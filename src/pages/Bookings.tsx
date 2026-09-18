import { useState } from "react";
import { Button, Empty, Resource, type Login } from "../components/ui";
import { openOfficial, useResource } from "../lib/api";
import { schoolToday } from "../lib/dates";
type Site = "sports" | "bdkj" | "library";
const sites = [
  {
    id: "sports" as Site,
    name: "体育场馆",
    description:
      "邱德拔、五四、第二体育场等运动场地。查看学校的实时场次表，选择场地与时间。",
  },
  {
    id: "bdkj" as Site,
    name: "教学研讨",
    description:
      "二教、四教、地学的研讨教室。先对照占用时段，再到原站填写参与人并预约。",
  },
  {
    id: "library" as Site,
    name: "图书馆研讨室",
    description: "图书馆独立预约系统。登录后查看房间、可约时段和已有预约。",
  },
];
export default function Bookings({ login }: { login: Login }) {
  const [site, setSite] = useState<Site>("bdkj");
  const [error, setError] = useState("");
  async function open() {
    setError("");
    try {
      if ("__TAURI_INTERNALS__" in window) {
        const { invoke } = await import("@tauri-apps/api/core");
        await invoke("open_booking", { site });
      } else await openOfficial(site);
    } catch {
      setError("预约窗口未能打开，可使用浏览器打开原站");
    }
  }
  const selected = sites.find((s) => s.id === site)!;
  return (
    <>
      <header className="page-heading">
        <h1>场地预约</h1>
      </header>
      <div className="tabs">
        {sites.map((s) => (
          <button
            key={s.id}
            className={site === s.id ? "active" : ""}
            onClick={() => {
              setSite(s.id);
              setError("");
            }}
          >
            {s.name}
          </button>
        ))}
      </div>
      <section className="resource booking-intro">
        <div>
          <h2>{selected.name}</h2>
          <p>{selected.description}</p>
        </div>
        <div className="submission-actions">
          <Button variant="primary" onClick={() => void open()}>
            打开预约窗口
          </Button>
          <Button onClick={() => void openOfficial(site)}>浏览器打开</Button>
        </div>
        <p className="footnote">
          预约窗口使用学校原站；登录、选择场次和确认预约均在窗口内完成。
        </p>
        {error && <p role="alert">{error}</p>}
      </section>
      {site === "bdkj" ? (
        <DiscussionGrid login={login} />
      ) : (
        <section className="resource">
          <h2>查看时间 × 场地</h2>
          <p className="section-description">
            当前在学校预约窗口查看实时表格。OnePKU
            尚未接入此系统的时段数据，预约结果请以学校“我的预约”为准。
          </p>
        </section>
      )}
    </>
  );
}
type Grid = {
  date: string;
  rooms: {
    id: string;
    name: string;
    capacity: string;
    locked: number;
    error: boolean;
    slots: { hour: number; state: "occupied" | "unknown" | "unlisted" }[];
  }[];
};
const labels = { occupied: "已占用", unknown: "未获取", unlisted: "未列占用" };
function DiscussionGrid({ login }: { login: Login }) {
  const [date, setDate] = useState(schoolToday());
  const [building, setBuilding] = useState("二教");
  const [tab, setTab] = useState("grid");
  const [period, setPeriod] = useState("afternoon");
  const hours =
    period === "morning"
      ? [7, 8, 9, 10, 11]
      : period === "afternoon"
        ? [12, 13, 14, 15, 16, 17]
        : [18, 19, 20, 21, 22];
  const grid = useResource<Grid>(
    { kind: "bookingGrid", building, date },
    tab === "grid",
  );
  const applications = useResource<
    {
      id: string;
      room_name: string;
      status: string;
      begin_end: string;
      reason: string;
    }[]
  >({ kind: "bookingApplications" }, tab === "mine");
  return (
    <>
      <div className="toolbar booking-toolbar">
        <div className="booking-switch">
          <button
            className={tab === "grid" ? "active" : ""}
            onClick={() => setTab("grid")}
          >
            占用时段
          </button>
          <button
            className={tab === "mine" ? "active" : ""}
            onClick={() => setTab("mine")}
          >
            我的预约
          </button>
        </div>
        {tab === "grid" && (
          <>
            <label>
              日期{" "}
              <input
                type="date"
                aria-label="研讨教室日期"
                value={date}
                onChange={(e) => {
                  if (e.target.value) setDate(e.target.value);
                }}
              />
            </label>
            <label>
              时段{" "}
              <select
                aria-label="查看时段"
                value={period}
                onChange={(e) => setPeriod(e.target.value)}
              >
                <option value="morning">上午 07–12</option>
                <option value="afternoon">下午 12–18</option>
                <option value="evening">晚上 18–23</option>
              </select>
            </label>
            <label>
              教学楼{" "}
              <select
                value={building}
                onChange={(e) => setBuilding(e.target.value)}
              >
                {["二教", "四教", "地学"].map((b) => (
                  <option key={b}>{b}</option>
                ))}
              </select>
            </label>
          </>
        )}
      </div>
      {tab === "grid" && grid.data?.error?.code === "bookingProfile" && (
        <section className="resource booking-profile">
          <div>
            <h2>先完善预约资料</h2>
            <p>
              北大空间已登录。学校还需要联系电话和联系邮箱，填写后重新查询，即可查看时段表。
            </p>
          </div>
          <Button
            variant="primary"
            onClick={() => void openOfficial("bdkjProfile")}
          >
            完善预约资料
          </Button>
          <Button
            disabled={grid.isFetching}
            onClick={() => void grid.refetch()}
          >
            我已填写，重新查询
          </Button>
        </section>
      )}
      {tab === "grid" ? (
        grid.data?.error?.code === "bookingProfile" ? null : (
          <Resource
            title="教学研讨教室占用表"
            q={grid}
            service="bdkj"
            login={login}
            officialTarget="bdkj"
          >
            {(data) => (
              <>
                <p className="subtle">
                  每格为 1
                  小时，部分重叠也标为占用；“未列占用”不代表可预约，学校还会校验开放时间与预约条件。
                </p>
                {data.rooms.length ? (
                  <div className="table-scroll booking-scroll">
                    <table className="data-table booking-grid">
                      <thead>
                        <tr>
                          <th>教室 / 时间</th>
                          {hours.map((hour) => (
                            <th key={hour}>
                              {hour}:00–{hour + 1}:00
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {data.rooms.map((r) => (
                          <tr key={r.id}>
                            <th>
                              {r.name}
                              <small>
                                {r.capacity ? `${r.capacity} 人` : ""}
                              </small>
                            </th>
                            {r.slots
                              .filter((s) => hours.includes(s.hour))
                              .map((s) => (
                                <td
                                  key={s.hour}
                                  className={`slot-${s.state}`}
                                  title={`${r.name} ${s.hour}:00–${s.hour + 1}:00 · ${labels[s.state]}`}
                                >
                                  {labels[s.state]}
                                </td>
                              ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <Empty>学校未返回此教学楼的研讨教室</Empty>
                )}
              </>
            )}
          </Resource>
        )
      ) : (
        <Resource
          title="我的研讨教室预约"
          q={applications}
          service="bdkj"
          login={login}
          officialTarget="bdkj"
        >
          {(rows) =>
            rows.length ? (
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>教室</th>
                      <th>时段</th>
                      <th>状态</th>
                      <th>事由</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r, i) => (
                      <tr key={r.id || `${r.begin_end}:${i}`}>
                        <td>{r.room_name}</td>
                        <td>{r.begin_end}</td>
                        <td>{r.status}</td>
                        <td>{r.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty>学校未列出已有预约</Empty>
            )
          }
        </Resource>
      )}
    </>
  );
}
