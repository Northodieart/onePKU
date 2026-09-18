import { cp, mkdir } from "node:fs/promises";
// PDF.js needs Adobe CMaps to render and extract Chinese from non-Unicode PDFs.
for (const name of ["cmaps", "standard_fonts", "wasm"]) {
  await mkdir(`public/pdfjs/${name}`, { recursive: true });
  await cp(`node_modules/pdfjs-dist/${name}`, `public/pdfjs/${name}`, {
    recursive: true,
  });
}
