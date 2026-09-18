import { expect, test } from "vitest";
import { transactionAmount } from "../src/lib/api";
test("positive source amounts are not mistaken for income", () => {
  expect(transactionAmount(300, "consume")).toBe("−3.00");
  expect(transactionAmount(300, "refund")).toBe("+3.00");
  expect(transactionAmount(300, null)).toBe("3.00");
});
