import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { call, type Course, type Request, type Envelope } from "../lib/api";
import { resourceItems, type SearchItem } from "../lib/search";
import {
  emptySearchSnapshot,
  loadSearchSnapshot,
  saveSearchSnapshot,
  replaceSearchResource,
  resourceKey,
  snapshotItems,
} from "../lib/searchCache";

export function useSearchIndex(
  enabled: boolean,
  generation: string,
  allTerms: boolean,
  revision: number,
) {
  const client = useQueryClient();
  const cache = useRef(emptySearchSnapshot(generation));
  const [hydrated, setHydrated] = useState(false);
  const [state, setState] = useState({
    items: [] as SearchItem[],
    courses: [] as Course[],
    busy: false,
    done: 0,
    total: 0,
    errors: [] as string[],
    pdfs: 0,
  });
  // Warm the index as the app opens, before the user invokes search.
  useEffect(() => {
    let stopped = false;
    setHydrated(false);
    cache.current = emptySearchSnapshot(generation);
    void loadSearchSnapshot(generation)
      .then((snapshot) => {
        if (stopped || !snapshot) return;
        cache.current = snapshot;
        setState((s) => ({
          ...s,
          items: snapshotItems(snapshot),
          courses: snapshot.courses,
          pdfs: Object.values(snapshot.files).filter((f) => f.pdf).length,
        }));
      })
      .catch(() => {
        if (!stopped)
          setState((s) => ({
            ...s,
            errors: ["本机搜索缓存暂不可用，本次仍可搜索"],
          }));
      })
      .finally(() => {
        if (!stopped) setHydrated(true);
      });
    return () => {
      stopped = true;
    };
  }, [generation]);

  useEffect(() => {
    if (!enabled || !generation || !hydrated) return;
    let stopped = false,
      done = 0,
      total = 0;
    const snapshot = cache.current;
    const errors = new Set<string>();
    let saveTimer: ReturnType<typeof setTimeout> | undefined;
    const persist = () => {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => {
        if (!stopped)
          void saveSearchSnapshot(snapshot).catch(() => {
            if (!stopped)
              setState((s) => ({
                ...s,
                errors: [...s.errors, "索引未能保存，重启后需重新读取"],
              }));
          });
      }, 300);
    };
    const publish = (busy = true) => {
      if (stopped) return;
      setState({
        items: snapshotItems(snapshot),
        courses: snapshot.courses,
        busy,
        done,
        total,
        errors: [...errors],
        pdfs: Object.values(snapshot.files).filter((f) => f.pdf).length,
      });
    };
    const read = async (kind: string, course?: string) => {
      const request: Request = course ? { kind, course } : { kind };
      const env = await client.fetchQuery({
        queryKey: ["resource", request],
        queryFn: ({ signal }) => call<unknown[]>(request, signal),
        staleTime: revision || kind === "localMaterials" ? 0 : 300000,
        retry: false,
      });
      if (stopped) throw Error("已停止更新");
      if (env.generation !== generation)
        throw Error("账号已变化，请重新打开搜索");
      if (env.error) throw Error(env.error.message);
      if (env.stale) errors.add("部分来源正在使用上次读取的内容");
      return env.data;
    };
    publish();
    void (async () => {
      try {
        const courses = (await read("allCourses")) as Course[] | null;
        if (courses) {
          snapshot.courses = courses;
          const live = new Set(courses.map((c) => c.id));
          for (const [key, bucket] of Object.entries(snapshot.resources))
            if (bucket.items.some((i) => !live.has(i.course.id)))
              delete snapshot.resources[key];
          for (const [key, file] of Object.entries(snapshot.files))
            if (file.items.some((i) => !live.has(i.course.id)))
              delete snapshot.files[key];
          persist();
        }
      } catch (e) {
        errors.add(e instanceof Error ? e.message : "课程暂时无法更新");
      }
      if (stopped) return;
      const selected = snapshot.courses.filter((c) => allTerms || c.current);
      const jobs = selected.flatMap((c) =>
        [
          "localMaterials",
          "content",
          "videos",
          "courseNotices",
          "courseAssignments",
        ].map((kind) => ({ c, kind })),
      );
      total = jobs.length;
      // Seed existing native metadata without delaying cached full-text results.
      for (const job of jobs) {
        const key = resourceKey(job.c.id, job.kind);
        if (snapshot.resources[key]) continue;
        const cached = client.getQueryData<Envelope<unknown[]>>([
          "resource",
          { kind: job.kind, course: job.c.id },
        ]);
        if (cached?.generation === generation && cached.data && !cached.error)
          snapshot.resources[key] = {
            updated: 0,
            items: resourceItems(job.c, job.kind, cached.data),
          };
      }
      publish();
      const worker = async (queue: typeof jobs) => {
        while (queue.length && !stopped) {
          const job = queue.shift()!;
          const cached = snapshot.resources[resourceKey(job.c.id, job.kind)];
          try {
            if (
              job.kind === "localMaterials" ||
              revision ||
              !cached ||
              Date.now() - cached.updated > 300000
            ) {
              const values = await read(job.kind, job.c.id);
              if (stopped) return;
              if (values) {
                replaceSearchResource(
                  snapshot,
                  job.c.id,
                  job.kind,
                  resourceItems(job.c, job.kind, values),
                );
                persist();
              }
            }
          } catch (e) {
            if (!stopped)
              errors.add(
                `${job.c.name}：${e instanceof Error ? e.message : "暂时无法更新"}`,
              );
          }
          done++;
          publish();
        }
      };
      const localJobs = jobs.filter((j) => j.kind === "localMaterials");
      await Promise.all([worker(localJobs), worker(localJobs)]);
      if (stopped) return;
      const remoteJobs = jobs.filter((j) => j.kind !== "localMaterials");
      const metadata = Promise.all([worker(remoteJobs), worker(remoteJobs)]);
      const files = snapshotItems(snapshot).filter(
        (i) =>
          i.local &&
          !i.source.page &&
          /\.(pdf|txt|md|csv|srt|vtt)$/i.test(i.title),
      );
      for (const item of files) {
        if (stopped) return;
        if (snapshot.files[item.key]) continue;
        if (item.local!.bytes > 32 * 1024 * 1024) {
          errors.add("超过 32 MB 的文件仅搜索文件名");
          continue;
        }
        try {
          const env = await call<{ mime: string; base64: string }>({
            kind: "readLocalMaterial",
            course: item.course.id,
            id: item.local!.id,
          });
          if (stopped) return;
          if (env.generation !== generation) throw Error("账号已变化");
          if (env.error || !env.data)
            throw Error(env.error?.message ?? "无法读取");
          const extracted: SearchItem[] = [];
          const pdf = env.data.mime === "application/pdf";
          if (pdf) {
            const { pdfText } = await import("../lib/materialPdf");
            const result = await pdfText(env.data.base64, () => stopped);
            if (stopped) return;
            for (const p of result.pages)
              extracted.push({
                ...item,
                key: `${item.key}:p${p.page}`,
                body: p.text,
                source: { ...item.source, page: p.page },
                detail: `第 ${p.page} 页`,
              });
            if (!result.pages.length)
              errors.add("部分 PDF 没有可提取文字，仅搜索文件名");
            if (result.truncated) errors.add("长 PDF 已搜索前 400 页");
          } else {
            const text = new TextDecoder().decode(
              Uint8Array.from(atob(env.data.base64), (c) => c.charCodeAt(0)),
            );
            extracted.push({ ...item, body: text.slice(0, 500000) });
          }
          // Keep the original even for image-only PDFs so they are not reparsed each launch.
          snapshot.files[item.key] = {
            items: extracted.length ? extracted : [item],
            pdf,
          };
          persist();
        } catch (e) {
          errors.add(
            `${item.title}：${e instanceof Error ? e.message : String(e)}`,
          );
        }
        publish();
        // Yield between new files so input/paint stays responsive during first indexing.
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
      await metadata;
      publish(false);
      persist();
    })().catch((e) => {
      errors.add(e instanceof Error ? e.message : "无法更新搜索内容");
      publish(false);
    });
    return () => {
      stopped = true;
      clearTimeout(saveTimer);
    };
  }, [enabled, generation, hydrated, allTerms, revision, client]);
  return state;
}
