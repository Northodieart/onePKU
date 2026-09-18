import React from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { call, type Envelope, type Request } from "./lib/api";
import { NotificationProvider } from "./lib/notifications";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/search.css";
const client = new QueryClient();
async function start() {
  try {
    const snapshot = await call<
      { request: Request; envelope: Envelope<unknown> }[]
    >({ kind: "snapshot" });
    for (const row of snapshot.data ?? [])
      client.setQueryData(["resource", row.request], row.envelope, {
        updatedAt: Date.parse(row.envelope.updatedAt ?? "") || 0,
      });
  } catch {
    /* A failed snapshot does not block live resources. */
  }
  createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <QueryClientProvider client={client}>
        <NotificationProvider>
          <App />
        </NotificationProvider>
      </QueryClientProvider>
    </React.StrictMode>,
  );
}
void start();
