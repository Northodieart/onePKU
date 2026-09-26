# OnePKU Android

北大校园服务的 Android 客户端，与 macOS 桌面版同源的独立实现。Kotlin + Jetpack Compose（Material 3）、MVVM（ViewModel + StateFlow）、Hilt 依赖注入，最低 Android 8.0（API 26）。

## 功能

- 账号密码登录（IAAA），会话失效后自动重登；密码经 Android Keystore 加密的 EncryptedSharedPreferences 保存，不出本机
- 今日聚合：待交作业、课程通知、校园卡余额一览
- 教学网：课程列表（按学期分组）、课程通知、作业列表、课程资料（可下载打开）、教师成绩
- 作业：全部课程汇总（待交/全部/已截止筛选）、作业详情与提交历史、评分与评语
- 作业提交：单个文件、不超过 25 MB，明确确认后上传；上传后重新读取提交记录并下载回执做 SHA-256 核对，核对不一致或失败时只提示人工核对，不自动重发
- 成绩单：树洞成绩源，支持"全部课程"与"专业必修/限选"两种口径，各自给出 GPA 与加权平均分；学校返回的官方 GPA 仅适用于全部课程口径并标注来源，其余均为本地计算
- 培养方案：离线内置教务部公开的 2021/2023/2024/2025 版共 599 份方案（构建时从仓库根 `data/curriculum/` 同步，约 2MB）。按成绩与在修课程推断年级与专业（门户「单位」命中时先限定本院系，零重合不猜）；毕业总学分与各学分系列以圆环呈现，每类一页、左右滑动切换；匹配不上的课程进入"待确认"，可手动归入某一类或标记不计入；大学英语按 Y/A/B/C/C+/免修 分级计学分，不足 8 学分的差额按方案要求计入通识教育课。方案原文提供教务部整卷入口，不在应用内抽页
- 通知中心：学校/部门通知（校内门户）、本院通知、教务部、图书馆活动，应用内阅读正文
- 本院通知：登录校内门户后读取「单位」识别所在院系，已适配的学院（物理、计算机、信科）直接抓官网通知页，其余回退到门户部门通知按院系过滤；识别不准可在设置里手动选院系
- 空闲教室：按教学楼与今天/明天/后天查询 12 节占用情况
- 校历：官方 PDF 应用内翻阅（2025–2026、2026–2027 学年）
- 校园卡：余额（电子账户口径）、本月消费类支出累计、逐笔流水
- 设置：各服务单独重连/断开、退出登录

不做：视频回放、树洞发帖、校园卡充值等写操作——这些请使用学校原有页面。唯一的写入口是作业提交，且必须经过明确确认与回执核对。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35）。

```bash
cd android
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。Release 构建需自行配置签名。

培养方案数据不在本目录，构建时由 Gradle 从仓库根的 `data/curriculum/` 同步进 assets，因此必须连着整个仓库一起构建，不能只拷 `android/` 出去。

单元测试：Windows 上若项目路径含中文，Gradle 测试 worker 的 classpath 参数文件会被本机 GBK 解码搞坏（报 `ClassNotFoundException: GradleWorkerMain`）。先把项目映射成 ASCII 盘符再跑测试：

```powershell
subst V: .
cd V:\android
.\..\android\.tools\gradle-8.10.2\bin\gradle.bat testDebugUnitTest
```

## 从 GitHub 下载 APK

推送到 `Android` 分支后，GitHub Actions（`.github/workflows/android.yml`）自动构建并在运行产物（Artifacts）中提供 `onepku-android.apk`；发布 Release 时 APK 会附加到 Release 页面。

## 隐私

- 账号密码只用于登录学校服务，保存在本机 EncryptedSharedPreferences（AES256-GCM，密钥由 Android Keystore 托管）。
- 各服务会话 Cookie 存于应用私有目录；退出登录即全部清除。
- 不接入任何第三方统计或广告 SDK，网络请求只发往学校域名。

## 协议来源

网络层协议（IAAA 登录、教学网解析、树洞成绩、校园卡 JWT 链、门户通知/空闲教室等）移植自本仓库的 Rust 实现（`crates/`、`vendor/pkucli/`）。`course.pku.edu.cn` 证书链缺少中间证书，应用内置 GlobalSign AlphaSSL CA 2025 作为补充信任锚（`res/raw/globalsign_alphassl_ca_2025.pem`）。
