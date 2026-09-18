# 第三方项目与致谢

OnePKU 站在很多北大、清华同学的开源工作之上。这里列出直接复用代码、改写协议或参考设计的项目，以及各自的许可。详细的复用范围和本地改动见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。

## 代码或协议直接复用

| 项目                                                                     | 作者         | 许可       | 在 OnePKU 中的用法                                                                               |
| ------------------------------------------------------------------------ | ------------ | ---------- | ------------------------------------------------------------------------------------------------ |
| [pkucli](https://github.com/pkuinfo/pkucli)                              | pkuinfo team | MIT        | `vendor/pkucli/` 保留 0ad6dea 快照与本地补丁，是教学网、树洞、校园卡、门户等全部数据访问的运行时 |
| [pku-coe-notice-helper](https://github.com/ha0xin/pku-coe-notice-helper) | ha0xin       | MIT        | 门户与教务部通知的请求协议改写为 Rust；许可文本见 `docs/licenses/`                               |
| [PDF.js](https://github.com/mozilla/pdf.js)                              | Mozilla      | Apache-2.0 | 校历与课件 PDF 渲染                                                                              |

## 参考了公开结构，独立实现

| 项目                                                                | 作者            | 许可     | 参考内容                                                               |
| ------------------------------------------------------------------- | --------------- | -------- | ---------------------------------------------------------------------- |
| [PkuClaw](https://github.com/TheOne2006/PkuClaw)                    | TheOne2006      | 见其仓库 | 缓存优先、TTL 与旧数据标注约定；Blackboard 作业回执与反馈页面的选择器  |
| [RSSHub](https://github.com/DIYgod/RSSHub)                          | DIYgod 与贡献者 | MIT      | 用其北大路由目录发现信科通知来源；选择器按官网独立验证，未打包路由代码 |
| [PkuCampusAssistant](https://github.com/Deke-yo/PkuCampusAssistant) | Deke-yo         | 见其仓库 | 本地资料目录按学期、课程整理的做法                                     |

## 设计参考

| 项目                                           | 作者       | 许可                   | 参考内容                                       |
| ---------------------------------------------- | ---------- | ---------------------- | ---------------------------------------------- |
| [OneTHU](https://github.com/smartThise/OneTHU) | smartThise | 未在仓库中找到许可声明 | 信息架构、订阅与整张校历的呈现方式；未复制源码 |

## 官方数据来源

- 校历 PDF：[北京大学校历页](https://simso.pku.edu.cn/pages/ccSchoolCalendar.html)
- 培养方案：[教务部培养方案页](https://dean.pku.edu.cn/web/student_info.php?type=1&id=2) 公开的各年度文理科卷
- 成绩规则：[教务部本科生成绩评定规则](https://dean.pku.edu.cn/web/rules_info.php?id=173)

## 课程评价站点

课程详情页提供跳转链接，OnePKU 不抓取、不缓存这些站点的数据：

- [拼好课](https://www.pinhaoke.love)（[源码](https://github.com/WishingCat/Pinhaoke)，Zengji Tu）
- [非官方课程测评@北京大学](https://courses.pinzhixiaoyuan.com/)
- [PKUHUB](https://pkuhub.cn/)

## 主要依赖

Tauri 2、React 19、TanStack Query、Radix UI、hls.js、Lucide、reqwest、tokio、scraper。完整依赖与许可见 `package-lock.json` 与 `Cargo.lock`。可选字幕组件使用 Belle Whisper 中文模型，见 [docs/SUBTITLES.md](docs/SUBTITLES.md)。

如果你的项目被 OnePKU 参考但未在此列出，或希望调整表述，请开 issue。
