import { useEffect, useRef, useState, type RefObject } from "react";
import { Captions } from "lucide-react";
import { action, chooseSubtitleFile } from "../lib/api";
import { Button } from "./ui";

export type SubtitleCue = { start: number; end: number; text: string };
export type SubtitleStatus = {
  job: { state: string; message?: string; completed?: number; total?: number };
  document?: {
    source: string;
    cues: SubtitleCue[];
    progress?: { completed: number; total: number };
  } | null;
  canResume?: boolean;
  provider: string;
  available: boolean;
  setupMessage: string;
};
export function useReplaySubtitles(id: string | undefined) {
  const [data, setData] = useState<SubtitleStatus>();
  const [error, setError] = useState("");
  useEffect(() => {
    setData(undefined);
    setError("");
    if (!id) return;
    let live = true;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const next = await action<SubtitleStatus>({
          kind: "subtitleStatus",
          id: id!,
        });
        if (live && next.job) {
          setData(next);
          setError("");
        }
      } catch (error) {
        if (live)
          setError(
            error instanceof Error ? error.message : "字幕状态暂时无法读取",
          );
      }
      if (live) timer = setTimeout(poll, 3000);
    }
    void poll();
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [id]);
  return { data, setData, error, setError };
}

// Native TextTrack cues also render in the video's native fullscreen player.
export function useSubtitleTrack(
  element: RefObject<HTMLVideoElement | null>,
  cues: SubtitleCue[] | undefined,
  enabled: boolean,
  offset: number,
) {
  const track = useRef<TextTrack | null>(null);
  const serialized = JSON.stringify(cues ?? []);
  useEffect(() => {
    const video = element.current;
    if (
      !video ||
      typeof video.addTextTrack !== "function" ||
      typeof VTTCue === "undefined"
    )
      return;
    const current =
      track.current ?? video.addTextTrack("subtitles", "课程字幕", "zh");
    track.current = current;
    current.mode = "hidden";
    Array.from(current.cues ?? []).forEach((cue) => current.removeCue(cue));
    const items: SubtitleCue[] = JSON.parse(serialized);
    for (const cue of items) {
      if (cue.end + offset <= 0) continue;
      // Text is literal; do not interpret imported markup in the player.
      const text = cue.text
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;");
      current.addCue(
        new VTTCue(Math.max(0, cue.start + offset), cue.end + offset, text),
      );
    }
    current.mode = enabled ? "showing" : "disabled";
    return () => {
      current.mode = "disabled";
    };
  }, [element, serialized, enabled, offset]);
}

export function SubtitleControls({
  id,
  state,
  enabled,
  setEnabled,
  offset,
  setOffset,
}: {
  id?: string;
  state: ReturnType<typeof useReplaySubtitles>;
  enabled: boolean;
  setEnabled: (value: boolean) => void;
  offset: number;
  setOffset: (value: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [more, setMore] = useState(false);
  const { data, setData, error } = state;
  const [actionError, setActionError] = useState("");
  const active =
    data?.job && ["preparing", "transcribing"].includes(data.job.state);
  const hasCues = Boolean(data?.document?.cues.length);
  const progress = data?.document?.progress;
  const partial = Boolean(progress && progress.completed < progress.total);
  const canResume = data?.canResume !== false;
  const completed = active
    ? (data?.job.completed ?? progress?.completed ?? 0)
    : (progress?.completed ?? 0);
  const time = `${Math.floor(completed / 60)}:${String(Math.floor(completed % 60)).padStart(2, "0")}`;
  const status = active
    ? completed > 0
      ? `生成中 · ${time}`
      : "正在生成…"
    : partial
      ? `已生成 ${time}`
      : !data
        ? "正在读取字幕…"
        : "";
  const failure =
    actionError ||
    error ||
    (data?.job.state === "error" ? data.job.message : "");
  async function run(kind: "subtitleStart" | "subtitleCancel" | "import") {
    if (!id) return;
    setBusy(true);
    setActionError("");
    try {
      const next =
        kind === "import"
          ? await chooseSubtitleFile<SubtitleStatus>(id)
          : await action<SubtitleStatus>({ kind, id });
      if (next) {
        setData(next);
        setMore(false);
        if (kind !== "subtitleCancel") setEnabled(true);
      }
    } catch (error) {
      setActionError(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="subtitle-controls">
      <Button
        variant="quiet"
        aria-label="字幕选项"
        aria-expanded={open}
        disabled={!id}
        onClick={() => {
          setOpen(!open);
          setMore(false);
        }}
      >
        <Captions size={18} />
        {active ? "字幕生成中" : hasCues ? "字幕" : "添加字幕"}
      </Button>
      {open && (
        <div className="subtitle-panel">
          <div className="subtitle-main">
            {hasCues && (
              <label className="subtitle-toggle">
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={(event) => setEnabled(event.target.checked)}
                />
                显示字幕
              </label>
            )}
            <span className="subtitle-progress" role="status">
              {status}
            </span>
            {active ? (
              <Button
                variant="quiet"
                disabled={busy}
                onClick={() => void run("subtitleCancel")}
              >
                停止生成
              </Button>
            ) : partial || !hasCues ? (
              <Button
                disabled={busy || !id || !data}
                onClick={() =>
                  void run(data?.available ? "subtitleStart" : "import")
                }
              >
                {data?.available
                  ? partial
                    ? canResume
                      ? "继续生成"
                      : "重新生成"
                    : "生成字幕"
                  : "导入字幕"}
              </Button>
            ) : null}
            <Button
              variant="quiet"
              aria-label="字幕更多选项"
              aria-expanded={more}
              onClick={() => setMore(!more)}
            >
              更多
            </Button>
          </div>
          {more && (
            <div className="subtitle-settings">
              <div className="subtitle-actions">
                <Button
                  variant="quiet"
                  disabled={busy || active || !id}
                  onClick={() => void run("import")}
                >
                  导入 SRT / VTT
                </Button>
                {hasCues && !partial && (
                  <Button
                    variant="quiet"
                    disabled={busy || active || !id || !data?.available}
                    onClick={() => void run("subtitleStart")}
                  >
                    重新生成
                  </Button>
                )}
                {hasCues && (
                  <label className="subtitle-offset">
                    字幕延迟
                    <input
                      aria-label="字幕延迟秒数"
                      type="number"
                      min={-300}
                      max={300}
                      step={0.5}
                      defaultValue={offset}
                      onBlur={(event) => {
                        const value = event.currentTarget.valueAsNumber;
                        event.currentTarget.value = String(
                          Number.isFinite(value)
                            ? Math.max(-300, Math.min(300, value))
                            : offset,
                        );
                      }}
                      onChange={(event) => {
                        const value = event.target.valueAsNumber;
                        if (Number.isFinite(value))
                          setOffset(Math.max(-300, Math.min(300, value)));
                      }}
                    />
                    秒
                  </label>
                )}
              </div>
              <p className="subtitle-source">
                {data?.document?.source || data?.provider}
              </p>
              <a
                className="subtitle-settings-link"
                href={`#${encodeURIComponent("设置?section=subtitles")}`}
              >
                字幕模型设置
              </a>
              {!data?.available && (
                <p className="subtitle-source">{data?.setupMessage}</p>
              )}
            </div>
          )}
          {failure && (
            <p className="subtitle-error" role="alert">
              {failure}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
