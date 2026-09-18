//! Read-only Blackboard learning information. Keep raw score text: a pending
//! grade is not zero, and a course total is not an official transcript grade.
use super::*;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct LearningGrade {
    pub id: String,
    pub title: String,
    pub category: String,
    pub score: String,
    pub activity: String,
    pub updated: String,
    pub status: String,
}

fn text_at(row: scraper::ElementRef<'_>, selector: &str) -> String {
    row.select(&Selector::parse(selector).expect("static selector"))
        .next()
        .map(|e| {
            e.text()
                .collect::<String>()
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ")
        })
        .unwrap_or_default()
}

pub fn parse_learning_grades(html: &str) -> Result<Vec<LearningGrade>> {
    let dom = Html::parse_document(html);
    if dom
        .select(&Selector::parse("#grades_wrapper").unwrap())
        .next()
        .is_none()
    {
        return Err(anyhow!("未识别教学网成绩页面，请在原站核对"));
    }
    let mut grades = Vec::new();
    for row in
        dom.select(&Selector::parse("#grades_wrapper .sortable_item_row[role='row']").unwrap())
    {
        let id = row
            .value()
            .attr("id")
            .context("成绩项目缺少标识")?
            .to_string();
        let title = text_at(row, ".gradable > a, .gradable > span");
        if title.is_empty() {
            return Err(anyhow!("成绩项目标题无法识别"));
        }
        grades.push(LearningGrade {
            id,
            title,
            category: text_at(row, ".itemCat"),
            score: text_at(row, ".cell.grade"),
            activity: text_at(row, ".activityType"),
            updated: text_at(row, ".lastActivityDate"),
            status: text_at(row, ".gradeStatus"),
        });
    }
    Ok(grades)
}

impl CourseApi {
    pub async fn learning_grades(&self, course: &str) -> Result<Vec<LearningGrade>> {
        let entry = self
            .list_course_entries(course)
            .await?
            .into_iter()
            .find(|e| matches!(e.name.as_str(), "个人成绩" | "我的成绩" | "My Grades"))
            .context("本课程未开放个人成绩入口")?;
        let url = url::Url::parse(COURSE_BASE)?.join(&entry.url)?;
        if url.scheme() != "https"
            || url.host_str() != Some("course.pku.edu.cn")
            || !url.path().starts_with("/webapps/")
            || !url
                .query_pairs()
                .any(|(k, v)| k == "course_id" && v == course)
        {
            return Err(anyhow!("成绩入口不属于本课程"));
        }
        let response = self.client.get(url).send().await?.error_for_status()?;
        if response.url().host_str() != Some("course.pku.edu.cn")
            || response.url().path().contains("login")
        {
            return Err(anyhow!("登录已失效"));
        }
        let html = response.text().await?;
        if html.contains("name=\"loginForm\"") || html.contains("id=\"loginBox\"") {
            return Err(anyhow!("登录已失效"));
        }
        parse_learning_grades(&html)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn grades_keep_pending_and_score_text_distinct() {
        let rows=parse_learning_grades(r#"<div id="grades_wrapper"><div id="1" class="sortable_item_row" role="row"><div class="cell gradable"><span>练习</span><div class="itemCat">作业</div></div><div class="activityType">尚未评分</div><div class="cell grade">-</div></div><div id="2" class="sortable_item_row" role="row"><div class="gradable"><a>测验</a></div><div class="cell grade">0 / 10</div></div></div>"#).unwrap();
        assert_eq!(rows[0].score, "-");
        assert_eq!(rows[0].title, "练习");
        assert_eq!(rows[1].score, "0 / 10");
        assert!(parse_learning_grades("<h1>登录</h1>").is_err());
        assert!(parse_learning_grades("<div id='grades_wrapper'></div>")
            .unwrap()
            .is_empty());
    }
}
