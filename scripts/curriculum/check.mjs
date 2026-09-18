// 校验 data/curriculum/ 的结构与合计，打印覆盖情况。有结构错误时退出码 1。
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.resolve(here, "../../data/curriculum");
const index = JSON.parse(readFileSync(path.join(dir, "index.json"), "utf8"));
const errors = [];
const soft = [];
const seen = new Set();
const isNum = (n) => typeof n === "number" && Number.isFinite(n);

for (const entry of index.plans) {
  const plan = JSON.parse(readFileSync(path.join(dir, entry.file), "utf8"));
  const tag = `${plan.cohort} ${plan.title}`;
  if (seen.has(plan.id)) errors.push(`${tag}: id 重复 ${plan.id}`);
  seen.add(plan.id);
  if (plan.id !== entry.id) errors.push(`${tag}: 索引 id 与文件不一致`);
  if (!plan.school) soft.push(`${tag}: 缺少院系`);
  if (!plan.major) errors.push(`${tag}: 缺少专业名`);
  if (!plan.totalCredits || !isNum(plan.totalCredits.min))
    soft.push(`${tag}: 缺少毕业总学分`);
  if (!plan.degree) soft.push(`${tag}: 缺少学位类型`);
  if (!Array.isArray(plan.groups)) errors.push(`${tag}: groups 不是数组`);
  const groupIds = new Set();
  for (const g of plan.groups ?? []) {
    if (!g.id || !g.name) errors.push(`${tag}: 课程组缺少 id 或名称`);
    if (groupIds.has(g.id)) errors.push(`${tag}: 课程组 id 重复 ${g.id}`);
    groupIds.add(g.id);
    for (const c of g.courses ?? []) {
      if (!/^\d{8}$/.test(c.code))
        errors.push(`${tag}: 课程号格式错误 ${c.code}`);
      if (!c.name) errors.push(`${tag}: 课程 ${c.code} 缺少名称`);
      if (!isNum(c.credits))
        soft.push(`${tag}: 课程 ${c.code} ${c.name} 缺少学分`);
    }
    if (
      isNum(g.min) &&
      /必修|基础课|核心课/.test(g.name) &&
      g.courses?.length
    ) {
      const sum = g.courses.reduce((n, c) => n + (c.credits ?? 0), 0);
      if (sum < g.min)
        soft.push(
          `${tag}: ${g.id} ${g.name} 课程学分合计 ${sum} 低于要求 ${g.min}`,
        );
    }
  }
  if (!plan.requirements?.length) soft.push(`${tag}: 未解析出学分系列要求`);
  if (!(plan.groups ?? []).length) soft.push(`${tag}: 没有课程组`);
  if (plan.warnings?.length)
    soft.push(`${tag}: ${plan.warnings.length} 条解析警告`);
}

const cohorts = [...new Set(index.plans.map((p) => p.cohort))].sort();
console.log(
  `方案 ${index.plans.length} 份，年级 ${cohorts.join("、")}，院系 ${new Set(index.plans.map((p) => p.school)).size} 个`,
);
console.log(
  `课程行 ${index.plans.reduce((n, p) => n + p.courses, 0)} 条；带解析警告的方案 ${index.plans.filter((p) => p.warnings).length} 份`,
);
if (soft.length) {
  console.log(`\n需要人工核对（${soft.length}）：`);
  for (const s of soft.slice(0, 60)) console.log("  - " + s);
  if (soft.length > 60) console.log(`  … 其余 ${soft.length - 60} 条`);
}
if (errors.length) {
  console.error(`\n结构错误（${errors.length}）：`);
  for (const e of errors) console.error("  ✗ " + e);
  process.exit(1);
}
console.log("\n结构校验通过");
