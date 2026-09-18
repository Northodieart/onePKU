export async function openBrowser(url: string, title = "原文") {
  if ("__TAURI_INTERNALS__" in window) {
    const { invoke } = await import("@tauri-apps/api/core");
    await invoke("open_browser", { url, title });
  } else {
    window.open(url, "_blank", "noopener,noreferrer");
  }
}
