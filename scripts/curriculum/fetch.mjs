// 下载教务部公开的培养方案 PDF 并转成带版面的纯文本。
// 用法：node scripts/curriculum/fetch.mjs [卷 id ...]   不传参数则处理 volumes.json 全部。
// 依赖：poppler 的 pdftotext（brew install poppler）。输出在 .private/curriculum-src/，不进仓库。
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, statSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
export const sourceDir = path.join(root, ".private/curriculum-src");
export const manifest = JSON.parse(
  readFileSync(path.join(here, "volumes.json"), "utf8"),
);

export async function fetchVolume(volume) {
  mkdirSync(sourceDir, { recursive: true });
  const pdf = path.join(sourceDir, `${volume.id}.pdf`);
  const txt = path.join(sourceDir, `${volume.id}.txt`);
  if (!existsSync(pdf) || statSync(pdf).size < 1_000_000) {
    process.stderr.write(`下载 ${volume.title} … `);
    const res = await fetch(volume.url, { redirect: "follow" });
    if (!res.ok) throw new Error(`${volume.id}: HTTP ${res.status}`);
    const bytes = Buffer.from(await res.arrayBuffer());
    if (!bytes.subarray(0, 5).equals(Buffer.from("%PDF-")))
      throw new Error(`${volume.id}: 返回的不是 PDF，可能需要登录或链接已变`);
    await writeFile(pdf, bytes);
    process.stderr.write(`${(bytes.length / 1e6).toFixed(1)} MB\n`);
  }
  if (!existsSync(txt) || statSync(txt).mtimeMs < statSync(pdf).mtimeMs) {
    process.stderr.write(`转换 ${volume.id} 为文本 … `);
    execFileSync("pdftotext", ["-layout", "-enc", "UTF-8", pdf, txt], {
      stdio: ["ignore", "ignore", "ignore"],
    });
    process.stderr.write("完成\n");
  }
  return txt;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const wanted = process.argv.slice(2);
  const volumes = manifest.volumes.filter(
    (v) => wanted.length === 0 || wanted.includes(v.id),
  );
  if (volumes.length === 0) {
    console.error(
      `未知卷 id。可用：${manifest.volumes.map((v) => v.id).join("、")}`,
    );
    process.exit(1);
  }
  for (const v of volumes) await fetchVolume(v);
}
