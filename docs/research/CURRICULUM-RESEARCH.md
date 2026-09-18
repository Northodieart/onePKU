# 学校培养方案完成度接口核对（2026-09-10）

## 已验证

校内门户 → 教务部业务 → 培养方案提供三个入口：

- 查看教学计划：`portletId=jwbStuBiz13`
- 学分完成情况自查（分专业前）：`portletId=jwbStuBiz14`
- 毕业审查（在学期间可查看学分完成情况）：`portletId=jwbStuBiz15`

统一入口：`https://portal.pku.edu.cn/portal2017/util/portletRedir.do`。
本机登录后学校通过 `sims-443.w.pku.edu.cn` 转入 SIMS；不要将该网关域名硬编码为所有用户的唯一地址。

毕业审查页面：`/bkxjStatus/edu/pku/stu/status/bkxj/jpf/graleaveBK/stu/major-review.jsp`。
学校原生 JS 用 JSON 接口读取数据，路径前缀为 `/bkxjStatus/edu/pku/stu/status/bkxj/jpf/graleaveBK/stu/`：

- `getByscztxxZxBKS.do`：状态、是否分配教学计划（`hasjxjh`）、教学计划名称、系列 `xl` 等。当前账号实测返回 success=true、hasjxjh=false、空计划名称与空系列；页面明确提示尚未分配教学计划。不能解释为 0% 或无需修读。
- `getByscGlZxBKS.do`：学校脚本读取系列明细 `byscxlxx`，其中 `xlxfyq` 为学分要求、`xlyhxf` 为已获学分；当前账号无计划，未取得有内容的该接口样本。
- `getByscPrlXlglByXlidZxBKS.do`：学校脚本读取并行系列明细；未以当前账号验证有内容样本。

当前已证实存在学校网页内部 JSON 接口，未发现公开承诺或稳定开放 API 文档。OnePKU 尚未接入这些接口。

## 接入约束

只读查询、刷新错误隔离、登录失效提示；学校未分配计划时直接显示该状态。认证经学校正常入口建立，凭证不传给前端。

学校页面有刷新匹配、刷新课类、调配课程和提交审查等写操作；不能把这些绑定成普通读取或自动重试。没有执行这些操作。

区分已通过、超出约束范围、未通过、在修，并直接尊重学校模块和匹配结果；不能以树洞总学分除以毕业学分推算完成度。

## 官方依据

- [教务部 2026 届毕业审查工作安排](https://dean.pku.edu.cn/web/notice_details.php?id=724)
- [城环学院教务办业务 FAQ](https://ues.pku.edu.cn/jyjx/bksjy/jxgl/xyjwbyw/index.htm)：本科四年期间可查看个人教学计划完成情况，自动匹配含当前学期选课。

## 分专业前自查的实际结果

真实页面为 `majorDemo/major-demo_list.jsp`，显示需先选择主修教学计划，再点击“开始匹配”。当前列表包括多个旧版及部分 2025 级方案，但未显示 2025 智能科学与技术普通本科方案（2024 版存在）。未替用户选择旧版或其他专业，未执行“开始匹配”，因此该路径尚未取得个人完成度结果。
