import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import UpdateSettings from "../src/components/UpdateSettings";
import { checkForUpdate } from "../src/lib/updater";

vi.mock("../src/lib/updater", () => ({
  APP_VERSION: "0.12.5",
  inApp: () => true,
  checkForUpdate: vi.fn(),
  relaunchApp: vi.fn(),
}));

beforeEach(() => vi.mocked(checkForUpdate).mockReset());
afterEach(() => cleanup());

it("says the app is up to date when a check finds nothing newer", async () => {
  vi.mocked(checkForUpdate).mockResolvedValue(null);
  render(<UpdateSettings />);
  fireEvent.click(screen.getByRole("button", { name: "检查更新" }));
  expect(await screen.findByRole("status")).toHaveTextContent("已是最新版本");
});
