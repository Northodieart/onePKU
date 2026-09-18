import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { FileUp, FileCheck2, LoaderCircle } from "lucide-react";
import {
  action,
  assignmentNeedsFile,
  chooseAssignmentFile,
  openAssignment,
  type Assignment,
  type StagedFile,
  type WriteOperation,
} from "../lib/api";
import { Button } from "./ui";
export function OperationStatus({
  operation,
  onUpdate,
}: {
  operation: WriteOperation;
  onUpdate?: (o: WriteOperation) => void;
}) {
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function change(kind: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await action<WriteOperation>({ kind, id: operation.id });
      onUpdate?.(next);
      await client.invalidateQueries({ queryKey: ["writeOperations"] });
      await client.invalidateQueries({
        queryKey: ["resource", { kind: "assignments" }],
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作未完成");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div
      className={`submission-result ${operation.state === "confirmed" ? "success" : ""}`}
      role="status"
    >
      <div className="submission-status-line">
        {operation.state === "sending" ? (
          <LoaderCircle size={18} className="spin" />
        ) : operation.state === "confirmed" ? (
          <FileCheck2 size={18} />
        ) : null}
        <strong>{operation.message}</strong>
      </div>
      {operation.receipt && <p>{operation.receipt}</p>}
      <div className="submission-actions">
        <Button
          onClick={() =>
            void openAssignment(operation.course, operation.content)
          }
        >
          在教学网查看
        </Button>
        {operation.state === "unknown" && (
          <>
            <Button
              disabled={busy}
              onClick={() => void change("recheckSubmission")}
            >
              重新核对学校记录
            </Button>
            <Button
              variant="quiet"
              disabled={busy}
              onClick={() => void change("endSubmission")}
            >
              已在原站核对，结束记录
            </Button>
          </>
        )}
      </div>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
export default function Submission({ assignment }: { assignment: Assignment }) {
  const client = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [file, setFile] = useState<StagedFile | null>(null);
  const [operation, setOperation] = useState<WriteOperation>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const mounted = useRef(true);
  const current = useRef<{
    file: StagedFile | null;
    operation?: WriteOperation;
    inFlight: boolean;
  }>({ file: null, inFlight: false });
  function update(o: WriteOperation) {
    current.current.operation = o;
    setOperation(o);
  }
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      const c = current.current;
      if (c.inFlight) return;
      if (c.operation?.state === "prepared")
        void action({ kind: "endSubmission", id: c.operation.id }).catch(
          () => {},
        );
      else if (!c.operation && c.file)
        void action({ kind: "discardStage", id: c.file.id }).catch(() => {});
    };
  }, []);
  async function choose() {
    setError("");
    setBusy(true);
    try {
      const next = await chooseAssignmentFile();
      if (next) {
        if (!mounted.current) {
          await action({ kind: "discardStage", id: next.id });
          return;
        }
        if (file) await action({ kind: "discardStage", id: file.id });
        setFile(next);
        current.current.file = next;
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }
  async function prepare() {
    if (!file || busy) return;
    setBusy(true);
    setError("");
    try {
      update(
        await action<WriteOperation>({
          kind: "prepareSubmission",
          course: assignment.course_id,
          content: assignment.content_id,
          file: file.id,
        }),
      );
      if (!mounted.current && current.current.operation) {
        await action({
          kind: "endSubmission",
          id: current.current.operation.id,
        });
        return;
      }
      void client.invalidateQueries({ queryKey: ["writeOperations"] });
    } catch (e) {
      setError(e instanceof Error ? e.message : "无法准备提交");
    } finally {
      setBusy(false);
    }
  }
  async function commit() {
    if (!operation || current.current.inFlight) return;
    current.current.inFlight = true;
    setBusy(true);
    setError("");
    update({
      ...operation,
      state: "sending",
      message: "正在提交并核对学校记录，请勿重复发送",
    });
    try {
      update(
        await action<WriteOperation>({
          kind: "commitSubmission",
          id: operation.id,
        }),
      );
    } catch {
      update({
        ...operation,
        state: "unknown",
        message: "连接中断，请在操作记录中核对结果，不要重复提交",
      });
    } finally {
      current.current.inFlight = false;
      setBusy(false);
      await client.invalidateQueries({ queryKey: ["writeOperations"] });
      await client.invalidateQueries({
        queryKey: ["resource", { kind: "assignments" }],
      });
    }
  }
  if (!assignmentNeedsFile(assignment))
    return (
      <section className="submission-panel">
        <Button
          onClick={() =>
            void openAssignment(assignment.course_id, assignment.content_id)
          }
        >
          在教学网查看
        </Button>
      </section>
    );
  if (operation && operation.state !== "prepared")
    return <OperationStatus operation={operation} onUpdate={update} />;
  return (
    <section className="submission-panel">
      <div className="submission-actions">
        <Button
          variant="primary"
          disabled={assignment.detail_error || !!assignment.last_attempt}
          onClick={() => setExpanded(true)}
        >
          <FileUp size={16} />
          提交作业
        </Button>
        <Button
          onClick={() =>
            void openAssignment(assignment.course_id, assignment.content_id)
          }
        >
          {assignment.last_attempt ? "在教学网追加或重新提交" : "在教学网办理"}
        </Button>
      </div>
      {expanded && (
        <>
          <p className="subtle">
            选择一个文件，最多 25 MB。多个文件可先打包成 ZIP。
          </p>
          {!operation ? (
            <>
              <Button disabled={busy} onClick={() => void choose()}>
                {file ? "更换附件" : "选择附件"}
              </Button>
              {file && (
                <div className="submission-file">
                  <strong>{file.name}</strong>
                  <span>{(file.bytes / 1024 / 1024).toFixed(2)} MB</span>
                </div>
              )}
              <Button disabled={!file || busy} onClick={() => void prepare()}>
                {busy ? "正在准备…" : "核对提交内容"}
              </Button>
            </>
          ) : (
            <div className="submission-review">
              <h3>确认这份作业</h3>
              <dl>
                <dt>课程</dt>
                <dd>{operation.courseName}</dd>
                <dt>作业</dt>
                <dd>{operation.title}</dd>
                <dt>附件</dt>
                <dd>
                  {operation.file.name} ·{" "}
                  {(operation.file.bytes / 1024 / 1024).toFixed(2)} MB
                </dd>
              </dl>
              <p className="subtle">
                将发送已选文件的当前副本，确认后不会自动重发。
              </p>
              <Button
                variant="primary"
                disabled={busy}
                onClick={() => void commit()}
              >
                确认提交到教学网
              </Button>
            </div>
          )}
        </>
      )}
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
