//! Read-only assignment feedback. Selectors follow Blackboard's own attempt view;
//! a missing score stays missing, and a failed history page stays unavailable.
use super::*;
use futures::{stream, StreamExt};

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedbackAttempt {
    pub id: String,
    pub label: String,
    pub score: Option<String>,
    pub points_possible: Option<String>,
    pub feedback: Option<String>,
    pub files: Vec<Attachment>,
    pub feedback_links: Vec<Attachment>,
    pub url: String,
    pub unavailable: bool,
}
#[derive(Debug, Clone, serde::Serialize)]
pub struct AssignmentFeedback {
    pub attempts: Vec<FeedbackAttempt>,
    pub warnings: Vec<String>,
    pub url: String,
}
#[derive(Clone)]
struct AttemptLink {
    id: String,
    label: String,
    url: url::Url,
}

fn text(dom: &Html, css: &str) -> Option<String> {
    dom.select(&Selector::parse(css).unwrap())
        .next()
        .map(|e| {
            e.text()
                .collect::<Vec<_>>()
                .join(" ")
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
        })
        .filter(|s| !s.is_empty())
}
fn assignment_url(course: &str, content: &str) -> url::Url {
    let mut u = url::Url::parse(&format!(
        "{COURSE_BASE}/webapps/assignment/uploadAssignment"
    ))
    .unwrap();
    u.query_pairs_mut().extend_pairs([
        ("mode", "view"),
        ("course_id", course),
        ("content_id", content),
    ]);
    u
}
fn query(u: &url::Url, key: &str) -> Option<String> {
    u.query_pairs()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.into_owned())
}
fn attempt_link(raw: &str, course: &str, content: &str) -> Option<(String, url::Url)> {
    let base = assignment_url(course, content);
    let parsed = base.join(raw).ok()?;
    if parsed.scheme() != "https"
        || parsed.host_str() != Some("course.pku.edu.cn")
        || parsed.path() != base.path()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return None;
    }
    if query(&parsed, "course_id").as_deref() != Some(course)
        || query(&parsed, "content_id").as_deref() != Some(content)
        || query(&parsed, "action").is_some()
    {
        return None;
    }
    let id = query(&parsed, "attempt_id")?;
    if id.is_empty() || id.len() > 80 || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
    {
        return None;
    }
    let mut safe = base;
    safe.query_pairs_mut().append_pair("attempt_id", &id);
    if let Some(index) = query(&parsed, "currentAttemptIndex").and_then(|v| v.parse::<u32>().ok()) {
        safe.query_pairs_mut()
            .append_pair("currentAttemptIndex", &index.to_string());
    }
    Some((id, safe))
}
fn links(dom: &Html, selector: &str, course: &str, content: &str, files: bool) -> Vec<Attachment> {
    let base = assignment_url(course, content);
    dom.select(&Selector::parse(selector).unwrap())
        .filter_map(|a| {
            let u = base.join(a.value().attr("href")?).ok()?;
            if !matches!(u.scheme(), "https" | "http")
                || !u.username().is_empty()
                || u.password().is_some()
            {
                return None;
            }
            if files
                && (u.scheme() != "https"
                    || u.host_str() != Some("course.pku.edu.cn")
                    || u.path() != "/webapps/assignment/download"
                    || query(&u, "course_id").as_deref() != Some(course))
            {
                return None;
            }
            let name = a.text().collect::<Vec<_>>().join(" ").trim().to_string();
            (!name.is_empty()).then_some(Attachment {
                name,
                url: u.to_string(),
            })
        })
        .collect()
}
fn submitted_files(dom: &Html, course: &str, content: &str) -> Vec<Attachment> {
    let base = assignment_url(course, content);
    dom.select(&Selector::parse("#currentAttempt_submissionList > li").unwrap())
        .filter_map(|row| {
            let a = row
                .select(&Selector::parse("a.attachment").unwrap())
                .next()?;
            let name = a.text().collect::<String>().trim().to_string();
            let download = row
                .select(
                    &Selector::parse("a.dwnldBtn[href], a[href*='/webapps/assignment/download']")
                        .unwrap(),
                )
                .next()
                .or(Some(a))?;
            let u = base.join(download.value().attr("href")?).ok()?;
            if name.is_empty()
                || u.scheme() != "https"
                || u.host_str() != Some("course.pku.edu.cn")
                || u.path() != "/webapps/assignment/download"
                || query(&u, "course_id").as_deref() != Some(course)
            {
                return None;
            }
            Some(Attachment {
                name,
                url: u.to_string(),
            })
        })
        .collect()
}
fn parse_page(
    html: &str,
    course: &str,
    content: &str,
    expected: Option<&AttemptLink>,
) -> Result<(Option<FeedbackAttempt>, Vec<AttemptLink>)> {
    let dom = Html::parse_document(html);
    let recognized = dom
        .select(
            &Selector::parse("#currentAttempt, #currentAttempt_label, #uploadAssignmentFormId")
                .unwrap(),
        )
        .next()
        .is_some();
    anyhow::ensure!(recognized, "无法识别作业反馈页面");
    let mut seen = HashSet::new();
    let history = dom
        .select(&Selector::parse("#currentAttempt_attemptList a[href]").unwrap())
        .filter_map(|a| {
            let (id, url) = attempt_link(a.value().attr("href")?, course, content)?;
            if !seen.insert(id.clone()) {
                return None;
            }
            Some(AttemptLink {
                id,
                url,
                label: a
                    .text()
                    .collect::<Vec<_>>()
                    .join(" ")
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" "),
            })
        })
        .collect::<Vec<_>>();
    let Some(label) = text(&dom, "#currentAttempt_label") else {
        return Ok((None, history));
    };
    let files = submitted_files(&dom, course, content);
    let current_id = dom.select(&Selector::parse("#currentAttempt_attemptList li.current a[href], #currentAttempt_attemptList a.current[href]").unwrap()).find_map(|a|attempt_link(a.value().attr("href")?, course, content).map(|(id,_)| id)).or_else(|| files.iter().find_map(|f| url::Url::parse(&f.url).ok().and_then(|u| query(&u,"attempt_id"))));
    if let (Some(expected), Some(current)) = (expected, &current_id) {
        anyhow::ensure!(expected.id == *current, "学校返回了其他提交记录");
    }
    let known = expected
        .or_else(|| {
            current_id
                .as_ref()
                .and_then(|id| history.iter().find(|h| &h.id == id))
        })
        .or_else(|| history.iter().find(|h| h.label == label));
    let score = dom
        .select(&Selector::parse("#currentAttempt_grade").unwrap())
        .next()
        .and_then(|e| {
            let value = e
                .value()
                .attr("value")
                .map(str::to_owned)
                .unwrap_or_else(|| e.text().collect::<String>());
            let v = value.trim();
            (!v.is_empty() && v != "-" && v != "—").then(|| v.to_string())
        });
    let points_possible = text(&dom, "#currentAttempt_pointsPossible")
        .map(|s| s.trim_start_matches('/').trim().to_string())
        .filter(|s| !s.is_empty());
    let result = FeedbackAttempt {
        id: known
            .map(|h| h.id.clone())
            .or_else(|| current_id.clone())
            .unwrap_or_else(|| format!("current:{label}")),
        label,
        score,
        points_possible,
        feedback: text(
            &dom,
            "#currentAttempt_feedback .vtbegenerated, #currentAttempt_feedback",
        ),
        files,
        feedback_links: links(
            &dom,
            "#currentAttempt_feedback a[href]",
            course,
            content,
            false,
        ),
        url: known.map(|h| h.url.to_string()).unwrap_or_else(|| {
            let mut u = assignment_url(course, content);
            if let Some(id) = &current_id {
                u.query_pairs_mut().append_pair("attempt_id", id);
            }
            u.to_string()
        }),
        unavailable: false,
    };
    Ok((Some(result), history))
}
impl CourseApi {
    async fn feedback_page(&self, url: url::Url) -> Result<String> {
        let mut response = self.client.get(url).send().await?.error_for_status()?;
        anyhow::ensure!(
            response.url().scheme() == "https"
                && response.url().host_str() == Some("course.pku.edu.cn")
                && response.url().path() == "/webapps/assignment/uploadAssignment",
            "登录已失效或反馈入口不可用"
        );
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            anyhow::ensure!(bytes.len() + chunk.len() <= 8 * 1024 * 1024, "反馈页面过大");
            bytes.extend_from_slice(&chunk);
        }
        let html = String::from_utf8_lossy(&bytes).into_owned();
        anyhow::ensure!(
            !html.contains("name=\"loginForm\"") && !html.contains("id=\"loginBox\""),
            "登录已失效"
        );
        Ok(html)
    }
    pub async fn assignment_feedback(
        &self,
        course: &str,
        content: &str,
    ) -> Result<AssignmentFeedback> {
        let url = assignment_url(course, content);
        let html = self.feedback_page(url.clone()).await?;
        let (current, history) = parse_page(&html, course, content, None)?;
        let mut warnings = Vec::new();
        let mut attempts = current.into_iter().collect::<Vec<_>>();
        let remaining = history
            .into_iter()
            .filter(|h| !attempts.iter().any(|a| a.id == h.id))
            .collect::<Vec<_>>();
        if remaining.len() > 29 {
            warnings.push("提交记录较多，当前最多显示 30 次；其余请在教学网查看".into());
        }
        let results = stream::iter(remaining.into_iter().take(29).enumerate().map(
            |(order, link)| async move {
                let result = async {
                    let html = self.feedback_page(link.url.clone()).await?;
                    parse_page(&html, course, content, Some(&link))?
                        .0
                        .ok_or_else(|| anyhow!("提交记录为空"))
                }
                .await;
                (order, link, result)
            },
        ))
        .buffer_unordered(4)
        .collect::<Vec<_>>()
        .await;
        let mut results = results;
        results.sort_by_key(|(order, _, _)| *order);
        for (_, link, result) in results {
            match result {
                Ok(attempt) => attempts.push(attempt),
                Err(_) => {
                    warnings.push(format!("{} 未能更新", link.label));
                    attempts.push(FeedbackAttempt {
                        id: link.id,
                        label: link.label,
                        score: None,
                        points_possible: None,
                        feedback: None,
                        files: vec![],
                        feedback_links: vec![],
                        url: link.url.to_string(),
                        unavailable: true,
                    });
                }
            }
        }
        Ok(AssignmentFeedback {
            attempts,
            warnings,
            url: url.to_string(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn feedback_keeps_zero_pending_history_and_scoped_files_distinct() {
        let html = r#"<div id='currentAttempt'><h3 id='currentAttempt_label'>尝试 2</h3><ul id='currentAttempt_attemptList'><li class='current'><a href='/webapps/assignment/uploadAssignment?course_id=_1_1&amp;content_id=_2_1&amp;attempt_id=_4_1'>尝试 2</a></li><li><a href='/webapps/assignment/uploadAssignment?course_id=_1_1&amp;content_id=_2_1&amp;attempt_id=_3_1'>尝试 1</a></li></ul><input id='currentAttempt_grade' value='0'><span id='currentAttempt_pointsPossible'>/ 10</span><div id='currentAttempt_feedback'><div class='vtbegenerated'>请补充证明。<a href='/bbcswebdav/xid-42'>批注.pdf</a></div></div><ul id='currentAttempt_submissionList'><li><a class='attachment' href='/webapps/assignment/download?course_id=_1_1&amp;file_id=_5_1'>answer.pdf</a></li></ul></div><a class='attachment' href='/teacher'>教师附件</a>"#;
        let (a, history) = parse_page(html, "_1_1", "_2_1", None).unwrap();
        let a = a.unwrap();
        assert_eq!(a.score.as_deref(), Some("0"));
        assert_eq!(a.points_possible.as_deref(), Some("10"));
        assert_eq!(a.id, "_4_1");
        assert_eq!(history.len(), 2);
        assert_eq!(a.files.len(), 1);
        assert_eq!(a.feedback_links.len(), 1);
        assert!(a.feedback.unwrap().contains("请补充证明"));
        let pending = html.replace("value='0'", "value=''");
        assert!(parse_page(&pending, "_1_1", "_2_1", None)
            .unwrap()
            .0
            .unwrap()
            .score
            .is_none());
        assert!(parse_page(
            "<div id='uploadAssignmentFormId'></div>",
            "_1_1",
            "_2_1",
            None
        )
        .unwrap()
        .0
        .is_none());
        assert!(parse_page("<h1>系统错误</h1>", "_1_1", "_2_1", None).is_err());
    }
    #[test]
    fn history_never_follows_writes_foreign_courses_or_other_hosts() {
        for url in ["https://evil.example/webapps/assignment/uploadAssignment?course_id=_1_1&content_id=_2_1&attempt_id=_3_1", "/webapps/assignment/uploadAssignment?course_id=_8_1&content_id=_2_1&attempt_id=_3_1", "/webapps/assignment/uploadAssignment?action=newAttempt&course_id=_1_1&content_id=_2_1&attempt_id=_3_1", "javascript:alert(1)"] { assert!(attempt_link(url,"_1_1","_2_1").is_none()); }
        let good="/webapps/assignment/uploadAssignment?course_id=_1_1&content_id=_2_1&attempt_id=_3_1&currentAttemptIndex=2&nonce=secret";
        let (_, u) = attempt_link(good, "_1_1", "_2_1").unwrap();
        assert_eq!(query(&u, "mode").as_deref(), Some("view"));
        assert!(query(&u, "nonce").is_none());
    }
}
