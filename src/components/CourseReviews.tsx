import { useState } from "react";
import { ChevronDown } from "lucide-react";
import { action } from "../lib/api";
import { reviewLinks } from "../lib/reviews";
import { Button } from "./ui";

/** 课程详情页的“评课”下拉：跳转到校内同学维护的课程评价站点。 */
export default function CourseReviews({ course }: { course: string }) {
  const [error, setError] = useState("");
  const links = reviewLinks(course);
  return (
    <details
      className="action-menu review-menu"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null))
          e.currentTarget.open = false;
      }}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          e.currentTarget.open = false;
          e.currentTarget.querySelector("summary")?.focus();
        }
      }}
    >
      <summary className="button quiet" aria-label="查看课程评价">
        评课
        <ChevronDown size={16} aria-hidden="true" />
      </summary>
      <div
        className="action-menu-items"
        onClick={(e) => {
          if ((e.target as HTMLElement).closest("button:not(:disabled)")) {
            const details = e.currentTarget.parentElement as HTMLDetailsElement;
            details.open = false;
            details.querySelector("summary")?.focus();
          }
        }}
      >
        {links.map((link) => (
          <Button
            key={link.id}
            variant="quiet"
            title={
              link.prefills
                ? `在${link.name}搜索此课程`
                : `打开${link.name}，需手动搜索课程名`
            }
            onClick={() => {
              setError("");
              void action({ kind: "openLink", url: link.url }).catch(() =>
                setError(`${link.name}暂时未能打开`),
              );
            }}
          >
            {link.name}
            {!link.prefills && <span className="subtle">首页</span>}
          </Button>
        ))}
        <p className="review-menu-note">
          第三方站点，内容由同学自发维护，OnePKU 不保存其数据。
        </p>
        {error && <p role="alert">{error}</p>}
      </div>
    </details>
  );
}
