import type { Course } from "./api";
import { courseItem, type SearchItem } from "./search";

export type SearchBucket = { updated: number; items: SearchItem[] };
export type SearchSnapshot = {
  version: 1;
  generation: string;
  courses: Course[];
  resources: Record<string, SearchBucket>;
  files: Record<string, { items: SearchItem[]; pdf: boolean }>;
};
export const emptySearchSnapshot = (generation: string): SearchSnapshot => ({
  version: 1,
  generation,
  courses: [],
  resources: {},
  files: {},
});
export const resourceKey = (course: string, kind: string) =>
  `${course}:${kind}`;

// A local material ID includes account, inode, size and nanosecond mtime.
// Replacing a directory listing invalidates deleted/changed revisions and pages.
export function replaceSearchResource(
  snapshot: SearchSnapshot,
  course: string,
  kind: string,
  items: SearchItem[],
) {
  snapshot.resources[resourceKey(course, kind)] = {
    updated: Date.now(),
    items,
  };
  if (kind === "localMaterials") {
    const live = new Set(items.map((i) => i.key));
    for (const [key, file] of Object.entries(snapshot.files))
      if (file.items[0]?.course.id === course && !live.has(key))
        delete snapshot.files[key];
  }
}
export function snapshotItems(snapshot: SearchSnapshot) {
  const items = new Map<string, SearchItem>();
  snapshot.courses.forEach((c) => items.set(`course:${c.id}`, courseItem(c)));
  for (const resource of Object.values(snapshot.resources))
    for (const item of resource.items) {
      items.set(item.key, item);
      if (item.local)
        for (const page of snapshot.files[item.key]?.items ?? [])
          items.set(page.key, page);
    }
  const downloaded = new Set(
    [...items.values()].flatMap((i) =>
      i.local?.downloadId ? [i.local.downloadId] : [],
    ),
  );
  return [...items.values()].filter(
    (i) =>
      !i.attachment?.downloadId || !downloaded.has(i.attachment.downloadId),
  );
}

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("onepku-learning-search", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("snapshots");
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}
export async function loadSearchSnapshot(generation: string) {
  const db = await database();
  try {
    return await new Promise<SearchSnapshot | undefined>((resolve, reject) => {
      const request = db
        .transaction("snapshots")
        .objectStore("snapshots")
        .get(generation);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const value = request.result as SearchSnapshot | undefined;
        resolve(
          value?.version === 1 && value.generation === generation
            ? value
            : undefined,
        );
      };
    });
  } finally {
    db.close();
  }
}
export async function saveSearchSnapshot(snapshot: SearchSnapshot) {
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("snapshots", "readwrite");
      const store = tx.objectStore("snapshots");
      // Only the current session's index is retained on this device.
      store.clear();
      store.put(snapshot, snapshot.generation);
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}
