//! Independent classroom-recording catalogue. Authentication follows the
//! teaching site's normal recording SSO, while catalogue IDs remain separate
//! from Blackboard course IDs. Never persist bearer tokens in results.
use super::*;
use serde_json::Value;
const RECORDINGS: &str = "https://onlineroomse.pku.edu.cn";

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct RecordingSession {
    pub course_id: String,
    pub sub_id: String,
    pub course: String,
    pub title: String,
    pub teacher: String,
    pub room: String,
    pub begins: String,
    pub term: String,
    pub state: String,
    pub playable: bool,
    pub url: String,
}
fn scalar(v: &Value, key: &str) -> String {
    match &v[key] {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        _ => String::new(),
    }
}
fn numeric_id(s: &str) -> Result<()> {
    if s.is_empty() || s.len() > 20 || !s.chars().all(|c| c.is_ascii_digit()) {
        return Err(anyhow!("录播标识无效"));
    }
    Ok(())
}
pub(super) fn recording_blocked(v: &Value) -> bool {
    scalar(v, "publish") == "2"
        || scalar(v, "sub_show") == "no"
        || scalar(v, "sub_review_type") == "2"
        || !matches!(scalar(v, "deleted_at").as_str(), "" | "0" | "null")
}
fn session(v: &Value) -> Result<RecordingSession> {
    let course_id = scalar(v, "id");
    let sub_id = scalar(v, "sub_id");
    numeric_id(&course_id)?;
    numeric_id(&sub_id)?;
    let blocked = recording_blocked(v);
    let state = if blocked {
        "已下架或未开放"
    } else {
        match scalar(v, "sub_status").as_str() {
            "6" => "回放",
            "3" => "回放生成中",
            "1" => "直播中",
            "2" => "未开始",
            "5" => "暂无回放",
            _ => "状态待核对",
        }
    };
    Ok(RecordingSession {
        url: format!("{RECORDINGS}/livingroom?course_id={course_id}&sub_id={sub_id}"),
        course_id,
        sub_id,
        course: scalar(v, "title"),
        title: scalar(v, "sub_title"),
        teacher: scalar(v, "lecturer_name"),
        room: scalar(v, "room_name"),
        begins: scalar(v, "course_begin"),
        term: scalar(v, "term"),
        state: state.into(),
        playable: !blocked && scalar(v, "sub_status") == "6",
    })
}
fn response_list(v: &Value) -> Result<&Vec<Value>> {
    if v["code"].as_i64() != Some(0) {
        return Err(anyhow!(
            "独立回放服务未授权或暂时不可用，请重新登录教学网或打开原站"
        ));
    }
    v["list"].as_array().context("独立回放列表格式变化")
}
fn token_in_cookie(raw: &str) -> Result<String> {
    let decoded = url::form_urlencoded::parse(format!("v={raw}").as_bytes())
        .next()
        .map(|(_, v)| v.into_owned())
        .context("回放登录信息无效")?;
    let re = regex::Regex::new(r#"i:1;s:\d+:"([^"]+)""#).unwrap();
    re.captures(&decoded)
        .map(|c| c[1].to_string())
        .context("回放登录信息无效")
}
impl CourseApi {
    async fn recording_token(&self, force: bool) -> Result<String> {
        let cached = {
            let jar = self
                .cookie_store
                .lock()
                .map_err(|_| anyhow!("登录状态无法读取"))?;
            let found = jar
                .iter_unexpired()
                .find(|c| c.name() == "_token")
                .map(|c| c.value().to_string());
            found
        };
        if !force {
            if let Some(raw) = cached {
                if let Ok(token) = token_in_cookie(&raw) {
                    return Ok(token);
                }
            }
        }
        let courses = self.list_courses(false).await?;
        let mut connected = false;
        for c in courses.iter().take(40) {
            let videos = self.list_videos(&c.id, c.name()).await?;
            if let Some(video) = videos.first() {
                self.get_video_redirect_url(&video.url).await?;
                connected = true;
                break;
            }
        }
        if !connected {
            return Err(anyhow!("未能从当前课程建立回放登录，请在原站核对"));
        }
        let raw = {
            let jar = self
                .cookie_store
                .lock()
                .map_err(|_| anyhow!("登录状态无法读取"))?;
            let found = jar
                .iter_unexpired()
                .find(|c| c.name() == "_token")
                .map(|c| c.value().to_string());
            found
        }
        .context("回放服务未建立登录")?;
        token_in_cookie(&raw)
    }
    async fn recording_request(&self, path: &str, params: &[(&str, &str)]) -> Result<Value> {
        for retry in 0..2 {
            let token = self.recording_token(retry == 1).await?;
            let response = self
                .client
                .get(format!("{RECORDINGS}{path}"))
                .bearer_auth(token)
                .header("referer", RECORDINGS)
                .query(params)
                .timeout(std::time::Duration::from_secs(35))
                .send()
                .await?;
            if response.status().as_u16() == 401 && retry == 0 {
                continue;
            }
            if response.status().as_u16() == 401
                || response.url().host_str() != Some("onlineroomse.pku.edu.cn")
            {
                return Err(anyhow!(
                    "RECORDING_AUTH: 独立回放会话需要重新建立，请打开原站"
                ));
            }
            let response = response.error_for_status()?;
            if response
                .content_length()
                .is_some_and(|n| n > 16 * 1024 * 1024)
            {
                return Err(anyhow!("回放列表过大，请缩小查询范围"));
            }
            return Ok(response.json().await?);
        }
        Err(anyhow!("RECORDING_AUTH: 独立回放会话需要重新建立"))
    }
    pub async fn recordings_on(&self, date: &str, search: &str) -> Result<Vec<RecordingSession>> {
        use chrono::Datelike;
        let parsed = chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d").context("日期格式无效")?;
        if date.len() != 10
            || !(2000..=2100).contains(&parsed.year())
            || search.chars().count() > 100
        {
            return Err(anyhow!("查询范围无效"));
        }
        let response = self
            .recording_request(
                "/courseapi/v2/course-live/search-live-course-list",
                &[
                    ("need_time_quantum", "1"),
                    ("unique_course", "1"),
                    ("with_sub_duration", "1"),
                    ("search_time", date),
                    ("tenant", "226"),
                    ("course_student_type", ""),
                    ("sub_live_status", ""),
                    ("with_sub_data", "1"),
                    ("like_title", search),
                ],
            )
            .await?;
        let mut rows = Vec::new();
        let mut seen = HashSet::new();
        for group in response_list(&response)? {
            for v in group["list"].as_array().context("回放时段格式变化")? {
                let row = session(v)?;
                if seen.insert((row.course_id.clone(), row.sub_id.clone())) {
                    rows.push(row);
                }
            }
        }
        Ok(rows)
    }
    pub async fn recording_sessions(&self, course: &str) -> Result<Vec<RecordingSession>> {
        numeric_id(course)?;
        let response = self
            .recording_request(
                "/courseapi/v2/schedule/search-live-course-list",
                &[
                    ("all", "1"),
                    ("course_id", course),
                    ("with_sub_data", "1"),
                    ("with_room_data", "1"),
                ],
            )
            .await?;
        response_list(&response)?.iter().map(session).collect()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn closed_recordings_do_not_become_playable() {
        let mut row =
            serde_json::json!({"id":"123","sub_id":"456","sub_status":"6","sub_show":"yes"});
        assert!(session(&row).unwrap().playable);
        row["sub_show"] = Value::String("no".into());
        assert!(!session(&row).unwrap().playable);
        assert!(response_list(&serde_json::json!({"code":403,"list":[]})).is_err());
        assert!(numeric_id("1&all=1").is_err());
    }
    #[test]
    fn tokens_are_decoded_only_from_the_signed_cookie() {
        assert_eq!(
            token_in_cookie("abc%3A2%3A%7Bi%3A1%3Bs%3A5%3A%22a.b.c%22%3B%7D").unwrap(),
            "a.b.c"
        );
        assert!(token_in_cookie("oops").is_err());
    }
}
