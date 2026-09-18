import { useState } from "react";
import { action } from "../lib/api";
import {
  APP_VERSION,
  checkForUpdate,
  inApp,
  relaunchApp,
  RELEASES_URL,
  type UpdateHandle,
} from "../lib/updater";
import { Button } from "./ui";

type Phase =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "latest"; checkedAt: string }
  | { kind: "available"; update: UpdateHandle }
  | {
      kind: "downloading";
      update: UpdateHandle;
      done: number;
      total: number | null;
    }
  | { kind: "ready"; update: UpdateHandle }
  | { kind: "error"; message: string };

function mb(n: number) {
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

/** 设置页的“检查更新”：当前版本、检查、下载安装、重启。 */
export default function UpdateSettings() {
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const busy = phase.kind === "checking" || phase.kind === "downloading";

  async function check() {
    setPhase({ kind: "checking" });
    try {
      const update = await checkForUpdate();
      setPhase(
        update
          ? { kind: "available", update }
          : {
              kind: "latest",
              checkedAt: new Date().toLocaleTimeString("zh-CN"),
            },
      );
    } catch (e) {
      setPhase({
        kind: "error",
        message: `未能检查更新：${e instanceof Error ? e.message : String(e)}`,
      });
    }
  }

  async function install(update: UpdateHandle) {
    setPhase({ kind: "downloading", update, done: 0, total: null });
    try {
      await update.install((done, total) =>
        setPhase({ kind: "downloading", update, done, total }),
      );
      setPhase({ kind: "ready", update });
    } catch (e) {
      setPhase({
        kind: "error",
        message: `更新未能安装：${e instanceof Error ? e.message : String(e)}`,
      });
    }
  }

  return (
    <div className="update-settings">
      <div className="settings-facts">
        <div>
          <span>当前版本</span>
          <strong>v{APP_VERSION}</strong>
        </div>
        {phase.kind === "available" ||
        phase.kind === "downloading" ||
        phase.kind === "ready" ? (
          <div>
            <span>可用版本</span>
            <strong>v{phase.update.version}</strong>
          </div>
        ) : null}
      </div>
      <div className="submission-actions">
        {inApp() ? (
          <>
            {(phase.kind === "idle" ||
              phase.kind === "latest" ||
              phase.kind === "error" ||
              phase.kind === "checking") && (
              <Button
                variant="quiet"
                disabled={busy}
                onClick={() => void check()}
              >
                {phase.kind === "checking" ? "正在检查…" : "检查更新"}
              </Button>
            )}
            {phase.kind === "available" && (
              <Button
                variant="primary"
                onClick={() => void install(phase.update)}
              >
                下载并安装 v{phase.update.version}
              </Button>
            )}
            {phase.kind === "ready" && (
              <Button variant="primary" onClick={() => void relaunchApp()}>
                重新启动以完成更新
              </Button>
            )}
          </>
        ) : (
          <Button
            variant="quiet"
            onClick={() => void action({ kind: "openLink", url: RELEASES_URL })}
          >
            前往 Releases 页
          </Button>
        )}
        <Button
          variant="quiet"
          onClick={() => void action({ kind: "openLink", url: RELEASES_URL })}
        >
          更新记录
        </Button>
      </div>
      {phase.kind === "latest" && (
        <p role="status" className="subtle">
          已是最新版本（{phase.checkedAt} 检查）。
        </p>
      )}
      {phase.kind === "available" && phase.update.notes && (
        <p className="subtle update-notes">{phase.update.notes}</p>
      )}
      {phase.kind === "downloading" && (
        <p role="status" className="subtle">
          正在下载
          {phase.total
            ? ` ${mb(phase.done)} / ${mb(phase.total)}`
            : `… ${mb(phase.done)}`}
        </p>
      )}
      {phase.kind === "ready" && (
        <p role="status" className="subtle">
          已下载并安装到应用包，重新启动后生效。
        </p>
      )}
      {phase.kind === "error" && <p role="alert">{phase.message}</p>}
      {!inApp() && (
        <p className="subtle">浏览器预览模式不能自动更新，请在应用内使用。</p>
      )}
      <p className="subtle">
        更新包来自 GitHub Releases，安装前校验开发者签名；不收集任何使用数据。
      </p>
    </div>
  );
}
