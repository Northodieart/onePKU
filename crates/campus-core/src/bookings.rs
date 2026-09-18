use super::*;
use futures::{stream, StreamExt};

fn bounds(raw: &str, date: &str) -> Option<i32> {
    let time = if raw.len() >= 16 {
        if !raw.starts_with(date) {
            return None;
        }
        raw.get(11..16)?
    } else {
        raw.get(..5)?
    };
    let (h, m) = time.split_once(':')?;
    let (h, m) = (h.parse::<i32>().ok()?, m.parse::<i32>().ok()?);
    if h > 23 || m > 59 || h < 0 || m < 0 {
        return None;
    }
    Some(h * 60 + m)
}
fn occupancy(rows: &[pku_bdkj::api::HistoryTime], date: &str, from: i32, to: i32) -> &'static str {
    let mut unknown = false;
    for row in rows {
        match (bounds(&row.begin_time, date), bounds(&row.end_time, date)) {
            (Some(a), Some(b)) if b > a => {
                if a < to && b > from {
                    return "occupied";
                }
            }
            _ => unknown = true,
        }
    }
    if unknown {
        "unknown"
    } else {
        "unlisted"
    }
}
impl Core {
    pub(crate) async fn booking_grid(&self, building: &str, date: &str) -> Result<Value> {
        let date_value = chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d")?;
        let today = (chrono::Utc::now() + chrono::Duration::hours(8)).date_naive();
        if date.len() != 10 || (date_value - today).num_days().abs() > 30 {
            bail!("预约日期超出查询范围")
        }
        let building_id =
            pku_bdkj::api::building_id(building).ok_or_else(|| anyhow!("教学楼无效"))?;
        let api = pku_bdkj::api::BdkjApi::from_session()?;
        if !api.application_open().await? {
            bail!("BDKJ_BOOKING_CLOSED");
        }
        let mut rooms = api.list_rooms(building_id).await?;
        rooms.sort_by(|a, b| a.name.cmp(&b.name));
        if rooms.len() > 50 {
            bail!("教室列表已变化，请在原站核对")
        }
        let rows=stream::iter(rooms).map(|room| {
            let api=&api;
            async move {
                let result=api.history_time(&room.id,date).await;
                let slots=(7..23).map(|hour| {
                    let state=match &result {Ok(history)=>occupancy(history,date,hour*60,(hour+1)*60),Err(_)=>"unknown"};
                    json!({"hour":hour,"state":state})
                }).collect::<Vec<_>>();
                json!({"id":room.id,"name":room.name,"capacity":room.seating_capacity,"locked":room.locked,"slots":slots,"error":result.is_err(),"profileRequired":result.as_ref().err().is_some_and(|e|e.to_string().contains("BDKJ_PROFILE_REQUIRED"))})
            }
        }).buffered(4).collect::<Vec<_>>().await;
        if rows.iter().any(|r| r["profileRequired"] == true) {
            bail!("BDKJ_PROFILE_REQUIRED");
        }
        Ok(json!({"date":date,"rooms":rows}))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn row(a: &str, b: &str) -> pku_bdkj::api::HistoryTime {
        pku_bdkj::api::HistoryTime {
            id: "1".into(),
            begin_time: a.into(),
            end_time: b.into(),
            intervals: String::new(),
        }
    }
    #[test]
    fn grid_keeps_unknown_separate_and_uses_overlap() {
        assert_eq!(occupancy(&[], "2026-09-09", 540, 600), "unlisted");
        assert_eq!(
            occupancy(&[row("09:50", "10:40")], "2026-09-09", 540, 600),
            "occupied"
        );
        assert_eq!(
            occupancy(
                &[row("2026-09-09 09:00:00", "2026-09-09 09:50:00")],
                "2026-09-09",
                600,
                660
            ),
            "unlisted"
        );
        assert_eq!(
            occupancy(&[row("changed", "schema")], "2026-09-09", 540, 600),
            "unknown"
        );
    }
}
