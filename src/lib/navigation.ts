import { useCallback, useEffect, useState } from "react";

export function readLocation() {
  try {
    const value = decodeURIComponent(location.hash.slice(1));
    const split = value.indexOf("?");
    return {
      page: split < 0 ? value : value.slice(0, split),
      params: new URLSearchParams(split < 0 ? "" : value.slice(split + 1)),
    };
  } catch {
    return { page: "", params: new URLSearchParams() };
  }
}

export function pageLink(page: string, values: Record<string, string>) {
  const params = new URLSearchParams(values).toString();
  return page + (params ? `?${params}` : "");
}

export function usePageParams(page: string) {
  const read = useCallback(() => {
    const route = readLocation();
    return route.page === page ? route.params : new URLSearchParams();
  }, [page]);
  const [params, setParams] = useState(read);
  useEffect(() => {
    const update = () => setParams(read());
    window.addEventListener("hashchange", update);
    return () => window.removeEventListener("hashchange", update);
  }, [read]);
  const update = useCallback(
    (values: Record<string, string | null>) => {
      const next = read();
      for (const [key, value] of Object.entries(values)) {
        if (value) next.set(key, value);
        else next.delete(key);
      }
      setParams(next);
      location.hash = encodeURIComponent(
        pageLink(page, Object.fromEntries(next)),
      );
      const main = document.getElementById("main");
      if (main) {
        main.scrollTop = 0;
        main.focus();
      }
    },
    [page, read],
  );
  return [params, update] as const;
}
