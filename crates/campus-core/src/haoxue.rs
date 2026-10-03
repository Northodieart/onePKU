//! 好学课堂实录 App 接口。教学网的「视频」栏目只列教师手工发布的回放，
//! 按学生身份直接查好学平台才能拿到全部课次。公共参数与签名密钥取自好学
//! 安卓客户端，属于公开客户端常量，不是用户凭证；令牌只在本模块与会话存储
//! 之间流动，不进入返回值，也不进入 JavaScript。
use super::*;
use md5::{Digest as Md5Digest, Md5};

pub(crate) const SERVICE: &str = "haoxue";
const BASE: &str = "https://yjapise.pku.edu.cn/courseapi/";
const TENANT: &str = "d438cafb4c34b3b49b3a523bbef50203";
const SECRET: &str = "8225ca5fa4d56d6fde540db6024fba39";
const APP_ID: &str = "50";
const CLIENT: &str = "android";
const API_VERSION: &str = "3.12.0";

fn scalar(value: &Value, key: &str) -> String {
    match value.get(key) {
        Some(Value::String(text)) => text.trim().to_string(),
        Some(Value::Number(number)) => number.to_string(),
        Some(Value::Bool(flag)) => flag.to_string(),
        _ => String::new(),
    }
}
fn array(value: &Value, key: &str) -> Vec<Value> {
    value
        .get(key)
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}
/// 好学安卓端按键排序后拼接「键+值」再拼密钥做 MD5。键全部是 ASCII，
/// 这里的字节序与其 UTF-16 码元序一致。
pub(crate) fn signed(input: &[(&str, &str)]) -> Vec<(String, String)> {
    let mut params: Vec<(String, String)> = [
        ("tenantid", TENANT),
        ("tenantId", TENANT),
        ("api_version", API_VERSION),
        ("app_id", APP_ID),
        ("client", CLIENT),
    ]
    .into_iter()
    .map(|(key, value)| (key.to_string(), value.to_string()))
    .chain(
        input
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string())),
    )
    .collect();
    params.sort_by(|left, right| left.0.cmp(&right.0));
    let raw = params
        .iter()
        .map(|(key, value)| format!("{key}{value}"))
        .collect::<Vec<String>>()
        .join("")
        + SECRET;
    let digest = Md5::digest(raw.as_bytes());
    params.push((
        "sign".to_string(),
        digest.iter().map(|byte| format!("{byte:02x}")).collect(),
    ));
    params
}
/// 学校接口把业务失败放在 200 响应里，`err` 401 表示 app-login 令牌失效。
fn payload(body: &Value) -> Result<Value> {
    if scalar(body, "err") == "401" {
        bail!("好学登录已失效，请重新登录");
    }
    if body.get("success").and_then(Value::as_bool) == Some(false) {
        let message = scalar(body, "errMsg");
        bail!("{}", if message.is_empty() { "好学接口返回失败" } else { &message });
    }
    Ok(body.get("data").cloned().unwrap_or_else(|| body.clone()))
}
fn blocked(value: &Value) -> bool {
    scalar(value, "publish") == "2"
        || scalar(value, "sub_show") == "no"
        || scalar(value, "sub_review_type") == "2"
        || !matches!(
            scalar(value, "deleted_at").as_str(),
            "" | "0" | "null" | "None"
        )
}
/// 状态口径与独立录播目录一致（vendor recordings.rs），避免同一课堂在两处显示不同。
pub(crate) fn state(value: &Value) -> (String, bool) {
    if blocked(value) {
        return ("已下架或未开放".to_string(), false);
    }
    let status = match scalar(value, "sub_status") {
        ref s if !s.is_empty() => s.clone(),
        _ => scalar(value, "playback_status"),
    };
    let label = match status.as_str() {
        "6" => "回放",
        "3" => "回放生成中",
        "1" => "直播中",
        "2" => "未开始",
        "5" => "暂无回放",
        _ => "状态待核对",
    };
    (label.to_string(), status == "6")
}
pub(crate) fn courses(body: &Value) -> Result<Vec<Value>> {
    Ok(array(&payload(body)?, "lists"))
}
fn episodes_of(data: &Value) -> Vec<Value> {
    let rows = array(data, "sub_list");
    if rows.is_empty() {
        return array(data, "lists");
    }
    rows
}
/// `sub_content` 在按课次详情的接口里是对象，在换票接口里是 JSON 字符串。
fn sub_content(data: &Value) -> Option<Value> {
    match data.get("sub_content") {
        Some(Value::Object(_)) => data.get("sub_content").cloned(),
        Some(Value::String(text)) => serde_json::from_str(text).ok(),
        _ => None,
    }
}
#[derive(Clone, Debug, PartialEq, Serialize)]
pub(crate) struct Source {
    pub label: String,
    pub url: String,
    /// 只有分片播放列表能进入现有缓存与离线管线；单一文件仅能原站观看。
    pub segmented: bool,
}
fn is_school_media(url: &str) -> bool {
    url::Url::parse(url).is_ok_and(|parsed| {
        parsed.scheme() == "https"
            && parsed.username().is_empty()
            && parsed.password().is_none()
            && parsed.port().is_none()
            && parsed
                .host_str()
                .is_some_and(|host| host == "pku.edu.cn" || host.ends_with(".pku.edu.cn"))
    })
}
fn is_playlist(url: &str) -> bool {
    url::Url::parse(url)
        .ok()
        .and_then(|parsed| {
            parsed
                .path_segments()
                .and_then(|mut segments| segments.next_back().map(str::to_ascii_lowercase))
        })
        .is_some_and(|last| last.ends_with(".m3u8"))
}
/// 优先返回可分片播放的地址；`videoArr` 只有单一文件时明确说明原因，不伪装成可播放。
pub(crate) fn sources(body: &Value) -> Result<Vec<Source>> {
    let data = payload(body)?;
    let mut found: Vec<Source> = Vec::new();
    let variants: Vec<Value> = match data.get("videoArr") {
        Some(Value::Array(items)) => items.clone(),
        _ => vec![],
    };
    for (index, variant) in variants.iter().enumerate() {
        let url = scalar(variant, "path");
        if url.is_empty() || !is_school_media(&url) {
            continue;
        }
        let label = {
            let text = scalar(variant, "tag");
            if text.is_empty() {
                format!("线路 {}", index + 1)
            } else {
                text
            }
        };
        found.push(Source {
            label,
            segmented: is_playlist(&url),
            url,
        });
    }
    if found.iter().any(|source| source.segmented) {
        found.retain(|source| source.segmented);
        return Ok(found);
    }
    if !found.is_empty() {
        bail!("该课堂只提供单一视频文件，暂不支持分片缓存播放");
    }
    if let Some(content) = sub_content(&data) {
        let playback = content.get("save_playback").cloned().unwrap_or(Value::Null);
        let url = scalar(&playback, "contents");
        if !url.is_empty() && is_school_media(&url) {
            if scalar(&playback, "is_m3u8") != "yes" {
                bail!("该课堂只提供单一视频文件，暂不支持分片缓存播放");
            }
            found.push(Source {
                label: "默认".to_string(),
                url,
                segmented: true,
            });
            return Ok(found);
        }
    }
    bail!("该课堂没有可用的录像地址")
}
fn normalize(value: &str) -> String {
    value
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_lowercase()
}
/// 好学与教学网的课程标识不同源，只能按课程名认领。同名歧义不猜，
/// 未命中也不当作「没有回放」，而是明确说明来源核对过。
pub(crate) fn match_course(name: &str, rows: &[Value]) -> Result<Value> {
    let key = normalize(name);
    let hits: Vec<Value> = rows
        .iter()
        .filter(|row| normalize(&scalar(row, "course_name")) == key)
        .cloned()
        .collect();
    match hits.len() {
        0 => bail!("课堂实录未收录这门课，请在原站核对"),
        1 => Ok(hits[0].clone()),
        count => bail!("课堂实录有 {count} 门同名课程，需要人工确认是哪一门"),
    }
}
fn beijing(seconds: &str) -> String {
    let Ok(value) = seconds.parse::<i64>() else {
        return String::new();
    };
    let Some(time) = chrono::DateTime::<chrono::Utc>::from_timestamp(value, 0) else {
        return String::new();
    };
    time.with_timezone(&chrono::FixedOffset::east_opt(8 * 3600).unwrap())
        .format("%Y-%m-%d %H:%M")
        .to_string()
}
fn pick(row: &Value, keys: &[&str]) -> String {
    keys.iter()
        .map(|key| scalar(row, key))
        .find(|text| !text.is_empty())
        .unwrap_or_default()
}
/// 一次课次的回放行。`hash_id` 就是好学的课次标识，与 `courseId` 一起构成缓存键，
/// 所以从「课程」页还是「课堂实录」页进入同一节课，命中的是同一份分片缓存。
/// `course_name` 传空串表示按行自带的课程名显示（按日期浏览时）。
fn replay_row(course: &str, course_name: &str, row: &Value) -> Option<Value> {
    let (_, playable) = state(row);
    let episode = pick(row, &["sub_id", "id"]);
    let title = pick(row, &["sub_title", "sub_name", "course_name"]);
    let time = beijing(&pick(row, &["class_begin", "start_at"]));
    if !playable || episode.is_empty() || title.is_empty() || time.is_empty() {
        return None;
    }
    Some(json!({
        "title": title,
        "time": time,
        // 独立录播站的课堂页，按数字课程与课次标识定位；不是媒体地址。
        "url": lesson_page(course, &episode),
        // 回放解析与缓存都要用它向好学定位课次；前端界面只读 courseId/hash_id。
        "episodeId": episode,
        "courseId": course,
        "course_name": if course_name.is_empty() {
            pick(row, &["course_name"])
        } else {
            course_name.to_string()
        },
        "room": pick(row, &["room_name"]),
        "hash_id": episode,
    }))
}
fn counted(rows: &[Value], build: impl Fn(&Value) -> Option<Value>) -> (Vec<Value>, usize) {
    let mut out = vec![];
    let mut waiting = 0;
    for row in rows {
        match build(row) {
            Some(value) => out.push(value),
            None => waiting += 1,
        }
    }
    (out, waiting)
}
pub(crate) fn replay_rows(course: &str, course_name: &str, rows: &[Value]) -> (Vec<Value>, usize) {
    counted(rows, |row| replay_row(course, course_name, row))
}
/// 「按日期」的每一行自带课程标识，所以逐行取课程名与教室。
pub(crate) fn dated_rows(rows: &[Value]) -> (Vec<Value>, usize) {
    counted(rows, |row| {
        let course = pick(row, &["course_id"]);
        let name = pick(row, &["course_name"]);
        replay_row(&course, &name, row)
    })
}
/// 「按课程」目录行：课程名、教师、院系与学期，供课堂实录首页列表使用。
pub(crate) fn course_rows(rows: &[Value]) -> Vec<Value> {
    rows.iter()
        .filter_map(|row| {
            let id = pick(row, &["course_id", "id"]);
            if id.is_empty() {
                return None;
            }
            Some(json!({
                "courseId": id,
                "name": pick(row, &["course_name", "title", "sub_name"]),
                "teacher": pick(row, &["course_teacher", "teacher"]),
                "college": pick(row, &["course_college", "information"]),
                "term": pick(row, &["course_term", "term"]),
            }))
        })
        .collect()
}
/// 好学原站的课堂页地址；只在原站核对时用，不是媒体地址。
pub(crate) fn lesson_page(course: &str, episode: &str) -> String {
    format!("https://onlineroomse.pku.edu.cn/livingroom?course_id={course}&sub_id={episode}")
}
/// 分页翻页与原站一致：下一页只看返回是否还有内容，上一页由页码决定。
pub(crate) fn page_shape(page: u32, rows: &[Value]) -> Value {
    json!({
        "page": page,
        "rows": rows,
        "hasPrev": page > 1,
        "hasNext": !rows.is_empty(),
    })
}
/// app-login 交给原生桥的载荷形态不稳定：可能是对象、JSON 文本，或被再编码
/// 一层；与haoxue 同样最多展开四层，再在其中找带令牌的节点。
fn unwrap_payload(value: &Value) -> Value {
    let mut current = value.clone();
    for _ in 0..4 {
        let Some(text) = current.as_str() else { break };
        let text = text.trim();
        if let Ok(parsed) = serde_json::from_str::<Value>(text) {
            current = parsed;
            continue;
        }
        // 认证页有时把 JSON 再 percent-encode 一层，解不开就到此为止。
        let decoded = percent_encoding::percent_decode(text.as_bytes())
            .decode_utf8_lossy()
            .to_string();
        if decoded == text {
            break;
        }
        current = match serde_json::from_str::<Value>(&decoded) {
            Ok(parsed) => parsed,
            Err(_) => Value::String(decoded),
        };
    }
    current
}
fn find_token(value: &Value, depth: u8) -> Option<&Value> {
    if depth > 4 {
        return None;
    }
    for key in ["_token", "token"] {
        if value.get(key).and_then(Value::as_str).is_some_and(|text| !text.is_empty()) {
            return Some(value);
        }
    }
    if let Value::Object(entries) = value {
        for child in entries.values() {
            if let Some(found) = find_token(child, depth + 1) {
                return Some(found);
            }
        }
    }
    None
}
/// 返回（请求头令牌, 媒体 Cookie 令牌, 姓名, 账号）。媒体 Cookie 缺失时沿用请求头令牌。
pub(crate) fn parse_payload(payload: &Value) -> Result<(String, String, String, String)> {
    let expanded = unwrap_payload(payload);
    let found = find_token(&expanded, 0).ok_or_else(|| anyhow!("登录结果中没有令牌"))?;
    let token = found
        .get("_token")
        .or_else(|| found.get("token"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let cookie_token = found
        .get("_cookieToken")
        .or_else(|| found.get("cookieToken"))
        .or_else(|| found.get("cookie_token"))
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .unwrap_or(token.as_str())
        .to_string();
    Ok((
        token,
        cookie_token,
        scalar(found, "realname"),
        scalar(found, "account"),
    ))
}
/// 安卓 WebView 身份的中转页 HTML 里就有令牌：页面把它交给原生桥，参数写在源码中。
/// 依次尝试 `PKULoginSuccess(` 后的引号字符串与平衡花括号块，再退到全文第一个
/// 能解出令牌的花括号块；解不出来就是这次跳转没带令牌。
pub(crate) fn extract_payload(html: &str) -> Option<String> {
    let mut candidates: Vec<String> = vec![];
    if let Some(pos) = html.find("PKULoginSuccess") {
        let tail = &html[pos..];
        if let Some(raw) = quoted_argument(tail) {
            candidates.push(raw);
        }
        candidates.extend(brace_blocks(tail).into_iter().take(2));
    }
    candidates.extend(brace_blocks(html).into_iter().take(16));
    candidates
        .into_iter()
        .find(|raw| parse_payload(&Value::String(raw.clone())).is_ok())
}
/// `PKULoginSuccess('...')` 或 `("...")` 的引号参数，原样取出不反转义：
/// 后续的 unwrap 管线自己会解 URI 编码与层层 JSON。
fn quoted_argument(tail: &str) -> Option<String> {
    let open = tail.find('(')?;
    let bytes = tail.as_bytes();
    let mut i = open + 1;
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    let quote = *bytes.get(i)?;
    if quote != b'\'' && quote != b'"' {
        return None;
    }
    let mut end = i + 1;
    while end < bytes.len() {
        if bytes[end] == quote && bytes[end - 1] != b'\\' {
            return Some(tail[i + 1..end].to_string());
        }
        end += 1;
    }
    None
}
/// 字符串感知的平衡花括号块，按出现顺序产出。
fn brace_blocks(text: &str) -> Vec<String> {
    let bytes = text.as_bytes();
    let mut out = vec![];
    let mut depth = 0usize;
    let mut start = 0usize;
    let mut string: Option<u8> = None;
    let mut escaped = false;
    for (i, byte) in bytes.iter().enumerate() {
        if let Some(q) = string {
            if escaped {
                escaped = false;
            } else if *byte == b'\\' {
                escaped = true;
            } else if *byte == q {
                string = None;
            }
            continue;
        }
        match byte {
            b'\'' | b'"' => string = Some(*byte),
            b'{' => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            b'}' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    out.push(text[start..=i].to_string());
                    if out.len() >= 6 {
                        return out;
                    }
                }
            }
            _ => {}
        }
    }
    out
}
/// 诊断转储前把令牌类字段打码：保留页面结构，不保留任何凭证。
fn redact_tokens(text: &str) -> String {
    let pairs = regex::Regex::new(r#""((?:_)?(?:cookie_)?[tT]oken)"\s*:\s*"[^"]*""#).unwrap();
    let text = pairs.replace_all(text, r#""$1":"***""#).to_string();
    let query = regex::Regex::new(r#"((?:_)?(?:cookie_)?token=)[^&\s"']+"#).unwrap();
    query.replace_all(&text, "$1***").to_string()
}
/// 好学安卓客户端的 WebView 身份：中转页按 UA 决定要不要交出令牌。
pub(crate) const ANDROID_UA: &str = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36";
/// 浏览器窗口完成统一认证后落在中转页：拿窗口里的会话 cookie、以安卓身份
/// 再取一次中转页，直接从源码里解出令牌，与好学的实现同一条路径。
pub(crate) async fn relay_login(url: &str, cookies: &[(String, String)]) -> Result<Value> {
    let url = url::Url::parse(url).map_err(|_| anyhow!("中转页地址无效"))?;
    if url.scheme() != "https" || url.host_str() != Some("passport.pku.edu.cn") {
        bail!("中转页地址无效");
    }
    if cookies.len() > 100 {
        bail!("中转会话异常，请重新登录");
    }
    let header = cookies
        .iter()
        .map(|(name, value)| {
            format!(
                "{}={}",
                name.replace(['\r', '\n', ';'], ""),
                value.replace(['\r', '\n', ';'], "")
            )
        })
        .collect::<Vec<_>>()
        .join("; ");
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::limited(5))
        .build()?;
    let response = client
        .get(url.as_str())
        .header("User-Agent", ANDROID_UA)
        .header("Accept", "text/html")
        .header("Cookie", header)
        .send()
        .await?;
    let status = response.status();
    let final_url = response.url().clone();
    let text = response.text().await?;
    match extract_payload(&text) {
        Some(candidate) => save_session(&Value::String(candidate)),
        None => {
            // 诊断：页面结构一次一换，猜不中就如实留证。令牌类字段一律打码，
            // 这份文件可以安全地发给维护者。
            let marker = text.contains("PKULoginSuccess");
            let dir = Store::new(SERVICE)?.config_dir().to_path_buf();
            let redacted = redact_tokens(&text.chars().take(256 * 1024).collect::<String>());
            let dump = std::fs::write(dir.join("relay-debug.html"), redacted);
            let mut message = format!(
                "中转页未交出令牌（HTTP {status}，含 PKULoginSuccess：{}，长度 {}）",
                if marker { "是" } else { "否" },
                text.len()
            );
            if final_url.as_str() != url.as_str() {
                message.push_str(&format!("，重定向到 {}", final_url.path()));
            }
            if dump.is_ok() {
                message.push_str(&format!("；页面已存到 {}", dir.join("relay-debug.html").display()));
            }
            Err(anyhow!("{message}"))
        }
    }
}
/// 好学会话单独保存；令牌与 Cookie 只落在这份私有会话里，不进返回值。
pub(crate) fn save_session(payload: &Value) -> Result<Value> {
    let (token, cookie_token, name, account) = parse_payload(payload)?;
    if account.is_empty() {
        bail!("登录结果缺少账号标识，请重新登录");
    }
    let session = pkuinfo_common::session::Session {
        token,
        expires_at: None,
        uid: Some(account.clone()),
        created_at: chrono::Utc::now(),
        extra: json!({ "_cookieToken": cookie_token, "realname": name, "account": account }),
    };
    Store::new(SERVICE)?.save_session(&session)?;
    Ok(json!({ "connected": true, "name": name, "account": account }))
}
pub(crate) fn status() -> Value {
    match Store::new(SERVICE).ok().and_then(|store| store.load_session().ok().flatten()) {
        Some(session) => json!({
            "connected": !session.token.is_empty(),
            "name": session.extra.get("realname").and_then(Value::as_str).unwrap_or_default(),
            "account": session.extra.get("account").and_then(Value::as_str).unwrap_or_default(),
        }),
        None => json!({ "connected": false, "name": "", "account": "" }),
    }
}
pub(crate) fn clear_session() -> Result<()> {
    Store::new(SERVICE)?.clear()?;
    Ok(())
}
/// 教学网课程名常带学分、教师或班号后缀，好学检索未必认整串。先按全名找，
/// 再退到第一个括号或空格前的主标题；每条候选仍要求规范化后同名才算命中。
pub(crate) fn search_keys(name: &str) -> Vec<String> {
    let mut out = vec![name.trim().to_string()];
    let base = name
        .split(['(', '（', '【', ' '])
        .next()
        .unwrap_or_default()
        .trim()
        .to_string();
    if !base.is_empty() && normalize(&base) != normalize(&out[0]) {
        out.push(base);
    }
    out
}
/// 教学网课程名 → 好学课次列表。认领失败必须显式报出来，不能显示成「没有回放」。
/// 搜索是按页返回的，只翻第一页就会漏掉排在后面的课程，所以按页扫到空为止；
/// 每页行数原站没有文档，退化成「比上一页少就认为到底」，并设最多 10 页的上限。
pub(crate) async fn replays(name: &str) -> Result<(Vec<Value>, usize)> {
    let learner = Haoxue::from_session()?;
    let mut miss = None;
    for key in search_keys(name) {
        let mut found: Vec<Value> = vec![];
        let mut previous = usize::MAX;
        for page in 1..=10u32 {
            let rows = learner.list_courses(page, &key).await?;
            if rows.is_empty() || rows.len() < previous {
                found.extend(rows);
                break;
            }
            previous = rows.len();
            found.extend(rows);
        }
        match match_course(name, &found) {
            Ok(claimed) => {
                let course = scalar(&claimed, "course_id");
                if course.is_empty() {
                    bail!("课堂实录未收录这门课，请在原站核对");
                }
                let (rows, waiting) = replay_rows(&course, name, &learner.list_episodes(&course).await?);
                return Ok((rows, waiting));
            }
            Err(error) => miss = Some(error),
        }
    }
    Err(miss.unwrap_or_else(|| anyhow!("课堂实录未收录这门课，请在原站核对")))
}
/// 好学原生目录：按课程浏览，带原站的搜索与翻页。
pub(crate) async fn catalogue(page: u32, search: &str) -> Result<Value> {
    let learner = Haoxue::from_session()?;
    let page = page.max(1);
    Ok(page_shape(
        page,
        &course_rows(&learner.list_courses(page, search).await?),
    ))
}
/// 好学原生目录：按日期浏览当天全部课堂记录，每行都能直接播放。
pub(crate) async fn by_date(date: &str, page: u32) -> Result<Value> {
    let learner = Haoxue::from_session()?;
    let page = page.max(1);
    let (rows, waiting) = dated_rows(&learner.list_dates(date, page).await?);
    let mut shape = page_shape(page, &rows);
    shape["waiting"] = waiting.into();
    Ok(shape)
}
/// 一门课的全部课次；`course` 是好学的课程标识。
pub(crate) async fn course_episodes(course: &str) -> Result<Value> {
    let learner = Haoxue::from_session()?;
    let detail = learner.course_detail(course).await?;
    let name = pick(&detail, &["course_name", "title"]);
    let (rows, waiting) = replay_rows(course, &name, &episodes_of(&detail));
    Ok(json!({
        "courseId": course,
        "name": name,
        "teacher": pick(&detail, &["course_teacher", "teacher"]),
        "term": pick(&detail, &["term_name", "course_term", "term"]),
        "thumb": scalar(&detail, "thumb"),
        "rows": rows,
        "waiting": waiting,
    }))
}
/// 存档一次回放所需的稳定信息。播放列表地址会过期，所以只在真正下载时
/// 再向好学取一次，队列里只留课程与课次标识。
#[derive(Clone, Debug)]
pub(crate) struct ReplayRef {
    pub course: String,
    pub episode: String,
    pub title: String,
    pub time: String,
    pub name: String,
    pub semester: String,
}
pub(crate) async fn replay_ref(course: &str, episode: &str) -> Result<ReplayRef> {
    let learner = Haoxue::from_session()?;
    let detail = learner.course_detail(course).await?;
    let lesson = learner.replay(course, episode).await?;
    let row = episodes_of(&detail)
        .into_iter()
        .find(|row| pick(row, &["sub_id", "id"]) == episode)
        .unwrap_or_default();
    let label = |value: &str, fallback: String| {
        if value.is_empty() {
            fallback
        } else {
            value.to_string()
        }
    };
    Ok(ReplayRef {
        course: course.into(),
        episode: episode.into(),
        title: label(&pick(&row, &["sub_title", "sub_name"]), lesson.title),
        time: label(&beijing(&pick(&row, &["class_begin", "start_at"])), lesson.time),
        name: label(&pick(&detail, &["course_name", "title"]), lesson.course),
        semester: label(
            &pick(&detail, &["term_name", "course_term", "term"]),
            "课堂实录".into(),
        ),
    })
}
/// 观看进度回写好学，与原站一样按「距上次上报的秒数 + 当前播放位置」上报。
pub(crate) async fn record(
    course: &str,
    episode: &str,
    play_time: u64,
    seconds: u64,
) -> Result<Value> {
    let learner = Haoxue::from_session()?;
    let now = chrono::Utc::now().timestamp();
    // 单次上报最多按六小时计，避免本机时钟或前端状态异常把观看时长写成负数或天文数字。
    let watched = i64::try_from(seconds.min(6 * 3600)).unwrap_or(0).min(now);
    let start = now - watched;
    let value = learner
        .call(
            "v2/learn-record/save-learn-record",
            &[
                ("course_id", course),
                ("sub_id", episode),
                ("enter_time", &start.to_string()),
                ("start_time", &start.to_string()),
                ("last_time", &now.to_string()),
                ("learn_live_duration", ""),
                ("learn_video_duration", &watched.to_string()),
                ("play_time", &play_time.to_string()),
            ],
            true,
        )
        .await?;
    Ok(json!({ "saved": true, "echo": value }))
}
pub(crate) struct Haoxue {
    token: String,
    pub(crate) cookie_token: String,
    client: reqwest::Client,
}
impl Haoxue {
    pub(crate) fn from_session() -> Result<Self> {
        let session = Store::new(SERVICE)?
            .load_session()?
            .ok_or_else(|| anyhow!("未登录好学课堂实录"))?;
        let cookie_token = session
            .extra
            .get("_cookieToken")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        Ok(Self {
            token: session.token,
            cookie_token,
            client: reqwest::Client::builder()
                .cookie_store(false)
                .timeout(Duration::from_secs(15))
                .build()?,
        })
    }
    async fn call(&self, api: &str, input: &[(&str, &str)], post: bool) -> Result<Value> {
        if self.token.is_empty() {
            bail!("未登录好学课堂实录");
        }
        let url = format!("{BASE}{api}");
        let request = if post {
            let form: Vec<(String, String)> = input
                .iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect();
            self.client
                .post(url.as_str())
                .query(&signed(&[]))
                .form(&form)
        } else {
            self.client.get(url.as_str()).query(&signed(input))
        };
        let response = request
            .header("token", self.token.as_str())
            .header("Accept", "application/json")
            .send()
            .await?;
        let status = response.status();
        let text = response.text().await?;
        if status == reqwest::StatusCode::UNAUTHORIZED {
            bail!("好学登录已失效，请重新登录");
        }
        if !status.is_success() {
            bail!("好学接口 HTTP {status}");
        }
        let body: Value = serde_json::from_str(&text)
            .unwrap_or_else(|_| json!({"success": false, "errMsg": text}));
        payload(&body)
    }
    pub(crate) async fn list_courses(&self, page: u32, search: &str) -> Result<Vec<Value>> {
        let body = self
            .call(
                "v3/course/get-use-member",
                &[("page", &page.to_string()), ("search", search)],
                false,
            )
            .await?;
        courses(&body)
    }
    pub(crate) async fn list_dates(&self, date: &str, page: u32) -> Result<Vec<Value>> {
        let body = self
            .call(
                "v3/course/get-use-member-sub",
                &[("course_time", date), ("page", &page.to_string())],
                false,
            )
            .await?;
        courses(&body)
    }
    /// 一门课的原始详情：`term_name`、`sub_list` 等字段都在这里。
    pub(crate) async fn course_detail(&self, course: &str) -> Result<Value> {
        self.call(
            "v3/course/get-course-detail",
            &[("course_id", course)],
            false,
        )
        .await
    }
    pub(crate) async fn list_episodes(&self, course: &str) -> Result<Vec<Value>> {
        Ok(episodes_of(&self.course_detail(course).await?))
    }
    /// 一次课次的可播放线路与学校记录的观看位置。
    pub(crate) async fn replay(&self, course: &str, episode: &str) -> Result<Replay> {
        let data = self
            .call(
                "v3/course/get-course-sub-detail",
                &[("course_id", course), ("sub_id", episode)],
                false,
            )
            .await?;
        Ok(Replay {
            sources: sources(&data)?,
            title: scalar(&data, "title"),
            course: scalar(&data, "course_name"),
            time: beijing(&pick(&data, &["class_begin", "start_at"])),
        })
    }
}
#[derive(Clone, Debug)]
pub(crate) struct Replay {
    pub sources: Vec<Source>,
    pub title: String,
    pub course: String,
    /// 上课时间可能不在课次详情里，取不到就是空串。
    pub time: String,
}

#[cfg(test)]
mod extract_tests {
    use super::*;
    #[test]
    fn extracts_the_bridge_argument_from_the_relay_page() {
        let html = r#"<html><script>
          var data = {"account":"23001","_token":"abc123","_cookieToken":"ck"};
          window.bridge.PKULoginSuccess(JSON.stringify(data));
        </script></html>"#;
        let found = extract_payload(html).expect("应当解出令牌");
        let (token, cookie, _, account) = parse_payload(&Value::String(found)).unwrap();
        assert_eq!(token, "abc123");
        assert_eq!(cookie, "ck");
        assert_eq!(account, "23001");
    }
    #[test]
    fn diagnostics_redact_tokens_but_keep_the_page_structure() {
        let page = r#"{"_token":"abc123","account":"23001","note":"token=secret 保留"} <a href="x?_token=zzz">"#;
        let redacted = redact_tokens(page);
        assert!(!redacted.contains("abc123"));
        assert!(!redacted.contains("secret"));
        assert!(!redacted.contains("zzz"));
        assert!(redacted.contains("23001"));
        assert!(redacted.contains("_token"));
    }
    #[test]
    fn extracts_a_quoted_payload_and_tolerates_noise() {
        let html = r#"<div>{}</div><script>bridge.PKULoginSuccess('{"token":"t9","account":"1"}');</script>"#;
        let found = extract_payload(html).expect("引号形式也应当解出");
        let (token, _, _, _) = parse_payload(&Value::String(found)).unwrap();
        assert_eq!(token, "t9");
        // 没有令牌的页面不硬解。
        assert!(extract_payload("<html><body>请登录</body></html>").is_none());
        assert!(extract_payload(r#"{"other": 1}"#).is_none());
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn signature_matches_the_android_client() {
        let full = signed(&[
            ("page", "1"),
            ("search", "高等"),
            ("course_time", "2026-09-30"),
            ("sub_id", "123"),
            ("course_id", "45"),
        ]);
        assert_eq!(
            full.iter().find(|(key, _)| key == "sign").unwrap().1,
            "4d3e15026f6455c31153a439309cc565"
        );
        // tenantid 与 tenantId 必须同时存在，且都参与签名。
        assert_eq!(
            full.iter()
                .filter(|(key, _)| key == "tenantid" || key == "tenantId")
                .count(),
            2
        );
        assert_eq!(
            signed(&[])
                .iter()
                .find(|(key, _)| key == "sign")
                .unwrap()
                .1,
            "4561ada4ac7e55fc43b8695d10a54f22"
        );
    }
    #[test]
    fn states_and_unpublished_rows_follow_the_recording_rules() {
        let ready = json!({"sub_status": "6"});
        assert_eq!(state(&ready), ("回放".to_string(), true));
        for (row, label) in [
            (json!({"sub_status": "3"}), "回放生成中"),
            (json!({"playback_status": 6, "sub_status": ""}), "回放"),
            (json!({}), "状态待核对"),
            (
                json!({"sub_status": "6", "sub_show": "no"}),
                "已下架或未开放",
            ),
            (
                json!({"sub_status": "6", "publish": "2"}),
                "已下架或未开放",
            ),
            (
                json!({"sub_status": "6", "deleted_at": "2026-09-01"}),
                "已下架或未开放",
            ),
        ] {
            let (text, playable) = state(&row);
            assert_eq!(text, label, "{row:?}");
            assert_eq!(playable, label == "回放", "{row:?}");
        }
    }
    #[test]
    fn replay_sources_prefer_segmentable_variants() {
        let mixed = json!({"data": {"videoArr": [
            {"path": "https://resourcese.pku.edu.cn/v/lesson.mp4", "tag": "原始"},
            {"path": "https://resourcese.pku.edu.cn/v/lesson.m3u8?token=abc", "tag": "标清"}
        ]}});
        assert_eq!(
            sources(&mixed).unwrap(),
            vec![Source {
                label: "标清".to_string(),
                url: "https://resourcese.pku.edu.cn/v/lesson.m3u8?token=abc".to_string(),
                segmented: true
            }]
        );
        // 单一文件不能进缓存管线，必须显式失败而不是当作可播放。
        let single = json!({"data": {"videoArr": [
            {"path": "https://resourcese.pku.edu.cn/v/lesson.mp4"}
        ]}});
        assert!(sources(&single)
            .unwrap_err()
            .to_string()
            .contains("单一视频文件"));
    }
    #[test]
    fn fallback_content_accepts_object_and_encoded_string() {
        let playlist = "https://resourcese.pku.edu.cn/v/lesson.m3u8?token=abc";
        let object = json!({"data": {"sub_content": {
            "save_playback": {"is_m3u8": "yes", "contents": playlist}
        }}});
        assert_eq!(sources(&object).unwrap()[0].url, playlist);
        let encoded = json!({"data": {"sub_content": serde_json::to_string(
            &json!({"save_playback": {"is_m3u8": "yes", "contents": playlist}})
        ).unwrap()}});
        assert_eq!(sources(&encoded).unwrap()[0].url, playlist);
        let legacy = json!({"data": {"sub_content": {
            "save_playback": {"is_m3u8": "no", "contents": "https://resourcese.pku.edu.cn/v/lesson.mp4"}
        }}});
        assert!(sources(&legacy)
            .unwrap_err()
            .to_string()
            .contains("单一视频文件"));
        assert_eq!(
            sources(&json!({"data": {}})).unwrap_err().to_string(),
            "该课堂没有可用的录像地址"
        );
    }
    #[test]
    fn media_addresses_are_limited_to_school_hosts() {
        for url in [
            "http://resourcese.pku.edu.cn/v/a.m3u8",
            "https://pku.edu.cn.attacker.test/a.m3u8",
            "https://a:b@resourcese.pku.edu.cn/a.m3u8",
            "https://resourcese.pku.edu.cn:8443/a.m3u8",
            "https://evil.test/a.m3u8",
        ] {
            assert!(!is_school_media(url), "{url}");
        }
        assert!(is_school_media("https://resourcese.pku.edu.cn/v/a.m3u8"));
        // 只看路径末段，带查询串的播放列表仍算分片地址。
        assert!(is_playlist("https://resourcese.pku.edu.cn/v/a.m3u8?token=abc"));
        assert!(!is_playlist("https://resourcese.pku.edu.cn/v/a.mp4"));
    }
    #[test]
    fn business_failures_and_expired_tokens_are_explicit() {
        assert_eq!(
            payload(&json!({"err": 401}))
                .unwrap_err()
                .to_string(),
            "好学登录已失效，请重新登录"
        );
        assert_eq!(
            payload(&json!({"success": false, "errMsg": "course_id 无效"}))
                .unwrap_err()
                .to_string(),
            "course_id 无效"
        );
        // 没有 data 包装的响应直接当作数据本体。
        let bare = json!({"lists": [{"course_id": "9"}]});
        assert_eq!(payload(&bare).unwrap()["lists"][0]["course_id"], "9");
    }
    #[test]
    fn lists_are_read_from_both_catalogue_shapes() {
        let body = json!({"data": {"lists": [
            {"course_id": "9", "course_name": "高等数学", "sub_status": "6"}
        ]}});
        assert_eq!(courses(&body).unwrap().len(), 1);
        let detail = json!({"data": {"sub_list": [
            {"id": "12", "sub_title": "第 1 节", "sub_status": "6"}
        ]}});
        assert_eq!(episodes_of(&payload(&detail).unwrap())[0]["id"], "12");
        // 目录接口在部分版本里同样返回 lists，两种形态都要能读。
        let alt = json!({"data": {"lists": [{"id": "13"}]}});
        assert_eq!(episodes_of(&payload(&alt).unwrap())[0]["id"], "13");
    }
    #[test]
    fn course_claim_is_exact_or_explicitly_ambiguous() {
        let rows = vec![
            json!({"course_id": "91", "course_name": "高等数学 B"}),
            json!({"course_id": "92", "course_name": "高等数学B"}),
            json!({"course_id": "93", "course_name": "中国近现代史"}),
        ];
        // 空白差异不应当作两门课；这里刻意让两个同名写法都命中，暴露歧义。
        assert_eq!(
            match_course("高等数学 B", &rows)
                .unwrap_err()
                .to_string(),
            "课堂实录有 2 门同名课程，需要人工确认是哪一门"
        );
        assert_eq!(
            match_course(" 中国近现代史 ", &rows).unwrap()["course_id"],
            "93"
        );
        assert_eq!(
            match_course("量子力学", &rows)
                .unwrap_err()
                .to_string(),
            "课堂实录未收录这门课，请在原站核对"
        );
    }
    #[test]
    fn replay_rows_keep_the_existing_shape_and_skip_unfinished_sessions() {
        let rows = vec![
            json!({"sub_id": "1201", "sub_title": "第 3 次课", "class_begin": "1759291200", "sub_status": "6"}),
            json!({"sub_id": "1202", "sub_title": "第 4 次课", "class_begin": "1759377600", "sub_status": "3"}),
            json!({"sub_id": "", "sub_title": "", "class_begin": "", "sub_status": "6"}),
        ];
        let (videos, waiting) = replay_rows("91", "高等数学B", &rows);
        assert_eq!(waiting, 2);
        assert_eq!(videos.len(), 1);
        let row = &videos[0];
        // 时间必须是北京时间且以 YYYY-MM-DD 开头，课程页的日期标签依赖这个形状。
        let time = row["time"].as_str().unwrap();
        assert!(time.starts_with("2025-10-01 "), "{time}");
        assert_eq!(row["title"], "第 3 次课");
        assert_eq!(row["course_name"], "高等数学B");
        assert_eq!(
            row["url"],
            "https://onlineroomse.pku.edu.cn/livingroom?course_id=91&sub_id=1201"
        );
        // 缓存与续播位置认的是好学的课程与课次标识，列表刷新也不会换键。
        assert_eq!(row["courseId"], "91");
        assert_eq!(row["episodeId"], "1201");
        assert_eq!(row["hash_id"], "1201");
        let again = replay_rows("91", "高等数学B", &rows).0;
        assert_eq!(row["hash_id"], again[0]["hash_id"]);
    }
    #[test]
    fn dated_rows_carry_their_own_course_teacher_and_room() {
        let rows = vec![
            json!({"course_id": "42", "sub_id": "9001", "sub_name": "第 5 次课",
                   "course_name": "结构化学", "room_name": "理科一",
                   "start_at": "1760000000", "sub_status": "6"}),
            json!({"course_id": "42", "sub_id": "9002", "sub_name": "第 6 次课",
                   "course_name": "结构化学", "start_at": "1760086400", "sub_status": "2"}),
        ];
        let (videos, waiting) = dated_rows(&rows);
        assert_eq!(waiting, 1);
        assert_eq!(videos.len(), 1);
        assert_eq!(videos[0]["title"], "第 5 次课");
        assert_eq!(videos[0]["course_name"], "结构化学");
        assert_eq!(videos[0]["room"], "理科一");
        assert_eq!(videos[0]["courseId"], "42");
        assert_eq!(videos[0]["hash_id"], "9001");
    }
    #[test]
    fn catalogue_rows_and_paging_follow_the_original_site() {
        let rows = course_rows(&[
            json!({"course_id": "9", "course_name": "高等数学", "course_teacher": "李老师",
                   "course_college": "数学科学学院", "course_term": "2025-2026 上学期"}),
            json!({"course_id": "", "course_name": "缺标识的行"}),
        ]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["name"], "高等数学");
        assert_eq!(rows[0]["college"], "数学科学学院");
        assert_eq!(rows[0]["term"], "2025-2026 上学期");
        let first = page_shape(1, &rows);
        assert_eq!(first["hasPrev"], false);
        assert_eq!(first["hasNext"], true);
        let last = page_shape(2, &[]);
        assert_eq!(last["hasPrev"], true);
        assert_eq!(last["hasNext"], false);
    }
    #[test]
    fn login_payload_expands_until_a_token_appears() {
        let direct = json!({"_token": "t1", "_cookieToken": "c1", "realname": "张三", "account": "2200000000"});
        assert_eq!(
            parse_payload(&direct).unwrap(),
            (
                "t1".to_string(),
                "c1".to_string(),
                "张三".to_string(),
                "2200000000".to_string()
            )
        );
        // 载荷可能是 JSON 文本，也可能被再 percent-encode 一层。
        let json_text = json!(serde_json::to_string(&direct).unwrap());
        assert_eq!(parse_payload(&json_text).unwrap().1, "c1");
        let encoded = json!(percent_encoding::utf8_percent_encode(
            &serde_json::to_string(&direct).unwrap(),
            percent_encoding::NON_ALPHANUMERIC
        ).to_string());
        let (token, _, name, account) = parse_payload(&encoded).unwrap();
        assert_eq!(
            (token.as_str(), name.as_str(), account.as_str()),
            ("t1", "张三", "2200000000")
        );
        // 没有独立 Cookie 令牌时，媒体请求沿用请求头令牌。
        let nested = json!({"data": {"user": {"token": "t3", "realname": "李四", "account": "2100000000"}}});
        let (token, cookie, name, account) = parse_payload(&nested).unwrap();
        assert_eq!(
            (token.as_str(), cookie.as_str(), name.as_str(), account.as_str()),
            ("t3", "t3", "李四", "2100000000")
        );
        assert_eq!(
            parse_payload(&json!({"ok": true}))
                .unwrap_err()
                .to_string(),
            "登录结果中没有令牌"
        );
    }
    #[test]
    fn course_names_are_searched_twice_at_most() {
        // 全名先试；学分级括号后缀认不出来时退回主标题，但不再退更多层。
        assert_eq!(
            search_keys("高等数学（B）（上）"),
            vec!["高等数学（B）（上）".to_string(), "高等数学".to_string()]
        );
        assert_eq!(search_keys(" 普通心理学 "), vec!["普通心理学".to_string()]);
        assert_eq!(
            search_keys("AI 与语言"),
            vec!["AI 与语言".to_string(), "AI".to_string()]
        );
    }
}
