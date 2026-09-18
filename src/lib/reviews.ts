// 课程评价站点只提供跳转，OnePKU 不抓取、不缓存它们的数据。
// 站点维护者与许可见 THIRD-PARTY-NOTICES.md。
export type ReviewSite = {
  id: string;
  name: string;
  /** true 表示能把课程名带进站点搜索；false 只打开首页。 */
  prefills: boolean;
  url: (course: string) => string;
};

/** 去掉教学网课程名里的班号、学期等后缀，只留课程名本身用于搜索。 */
export function reviewQuery(name: string): string {
  return name
    .replace(/[（(]\s*\d{2}-\d{2}[^）)]*[）)]\s*$/u, "")
    .replace(/[（(]\s*\d+\s*班\s*[）)]\s*$/u, "")
    .replace(/\s+/g, " ")
    .trim();
}

export const reviewSites: ReviewSite[] = [
  {
    id: "pinhaoke",
    name: "拼好课",
    prefills: true,
    url: (course) =>
      `https://www.pinhaoke.love/reviews?q=${encodeURIComponent(course)}`,
  },
  {
    id: "pinzhixiaoyuan",
    name: "非官方课程测评",
    prefills: false,
    url: () => "https://courses.pinzhixiaoyuan.com/",
  },
  {
    id: "pkuhub",
    name: "PKUHUB",
    prefills: false,
    url: () => "https://pkuhub.cn/",
  },
];

export function reviewLinks(courseName: string) {
  const query = reviewQuery(courseName);
  return reviewSites.map((site) => ({
    id: site.id,
    name: site.name,
    prefills: site.prefills,
    url: site.url(query),
  }));
}
