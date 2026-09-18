use super::*;
use base64::Engine;
#[derive(Clone)]
pub struct Attempt {
    client: reqwest::Client,
    service: String,
    app: String,
    redirect: String,
    device: String,
    expires: std::time::Instant,
}
impl Core {
    pub(crate) async fn auth_begin(&self, service: &str) -> Result<Value> {
        let store = Store::new(match service {
            "course" | "treehole" | "campuscard" | "bdkj" => service,
            _ => bail!("invalid service"),
        })?;
        let device = if service == "treehole" {
            pku_treehole::login::get_device_uuid(&store)
        } else {
            String::new()
        };
        let config = match service {
            "course" => pku_course::login::iaaa_config(),
            "bdkj" => pku_bdkj::login::iaaa_config(),
            "treehole" => pku_treehole::login::iaaa_config(&device),
            _ => pku_campuscard::login::iaaa_config(),
        };
        let client = pku_course::client::build_simple()?;
        client
            .get("https://iaaa.pku.edu.cn/iaaa/oauth.jsp")
            .query(&[
                ("appID", &config.app_id),
                ("redirectUrl", &config.redirect_url),
            ])
            .send()
            .await?
            .error_for_status()?
            .bytes()
            .await?;
        let bytes = client
            .get("https://iaaa.pku.edu.cn/iaaa/genQRCode.do")
            .query(&[
                ("userName", ""),
                ("appId", &config.app_id),
                ("_rand", &rand::random::<f64>().to_string()),
            ])
            .header("referer", "https://iaaa.pku.edu.cn/iaaa/oauth.jsp")
            .send()
            .await?
            .error_for_status()?
            .bytes()
            .await?;
        let mime = qr_mime(&bytes)?;
        let id = format!("{:032x}", rand::random::<u128>());
        let mut attempts = self.auth.lock().unwrap();
        attempts.retain(|_, a| a.expires > std::time::Instant::now() && a.service != service);
        if attempts.len() > 3 {
            attempts.clear()
        }
        attempts.insert(
            id.clone(),
            Attempt {
                client,
                service: service.into(),
                app: config.app_id,
                redirect: config.redirect_url,
                device,
                expires: std::time::Instant::now() + Duration::from_secs(180),
            },
        );
        Ok(
            json!({"id":id,"qr":format!("data:{mime};base64,{}",base64::engine::general_purpose::STANDARD.encode(bytes)),"state":"pending"}),
        )
    }
    pub(crate) async fn auth_poll(&self, id: &str) -> Result<Value> {
        let a = self
            .auth
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .ok_or_else(|| anyhow!("二维码已过期"))?;
        if a.expires < std::time::Instant::now() {
            self.auth.lock().unwrap().remove(id);
            return Ok(json!({"state":"expired"}));
        }
        let v: Value = a
            .client
            .post("https://iaaa.pku.edu.cn/iaaa/oauthlogin4QRCode.do")
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://iaaa.pku.edu.cn/iaaa/oauth.jsp")
            .form(&[
                ("appId", "PKUApp"),
                ("issuerAppId", "iaaa"),
                ("targetAppId", &a.app),
                ("redirectUrl", &a.redirect),
            ])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        if v["success"] == true {
            if self.auth.lock().unwrap().remove(id).is_none() {
                return Ok(json!({"state":"cancelled"}));
            }
            let token = v["token"].as_str().ok_or_else(|| anyhow!("empty token"))?;
            let store = Store::new(&a.service)?;
            match a.service.as_str() {
                "course" => pku_course::login::complete_bb_login(&store, token).await?,
                "bdkj" => pku_bdkj::login::complete_bdkj_login(&store, token, "").await?,
                "campuscard" => pku_campuscard::login::complete_login(&store, token, "").await?,
                _ => pku_treehole::login::complete_gui_login(&store, token, &a.device).await?,
            }
            if a.service == "course" {
                let _ = self.subtitle_account(&fingerprint("course")).await;
            }
            Ok(json!({"state":"success"}))
        } else {
            let code = v["errors"]["code"].as_str().unwrap_or("");
            Ok(
                json!({"state":if code=="E99"{"expired"}else if code=="E10"{"pending"}else{"failed"}}),
            )
        }
    }
    pub(crate) async fn sms(&self, scope: &str, code: Option<&String>) -> Result<Value> {
        if !matches!(scope, "treehole" | "timetable") {
            bail!("invalid scope")
        }
        if let Some(c) = code {
            if !(4..=8).contains(&c.len()) || !c.chars().all(|c| c.is_ascii_digit()) {
                bail!("invalid code")
            }
        }
        let store = Store::new("treehole")?;
        let session = store.load_session()?.ok_or_else(|| anyhow!("未登录"))?;
        let path = match (scope, code.is_some()) {
            ("treehole", false) => "jwt_send_msg",
            ("treehole", true) => "jwt_msg_verify",
            (_, false) => "course/send_get_token_message",
            (_, true) => "course/mobile_message_get_token",
        };
        let client = pku_treehole::client::build(store.load_cookie_store()?)?;
        let body = match code {
            None => json!({}),
            Some(c) => {
                if scope == "treehole" {
                    json!({"valid_code":c})
                } else {
                    json!({"code":c})
                }
            }
        };
        let v: Value = client
            .post(format!("https://treehole.pku.edu.cn/chapi/api/{path}"))
            .header("authorization", format!("Bearer {}", session.token))
            .header("uuid", session.extra["full_uuid"].as_str().unwrap_or(""))
            .json(&body)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        if v["success"] != true {
            if code.is_none() && v["message"].as_str().unwrap_or("").contains("未过期") {
                return Ok(json!({"state":"sent"}));
            }
            bail!("sms failed")
        }
        Ok(json!({"state":if code.is_some(){"success"}else{"sent"}}))
    }
}

fn qr_mime(bytes: &[u8]) -> Result<&'static str> {
    if bytes.len() > 2_000_000 {
        bail!("invalid qr");
    }
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Ok("image/png")
    } else if bytes.starts_with(b"\xff\xd8\xff") {
        Ok("image/jpeg")
    } else {
        bail!("invalid qr")
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn qr_uses_image_signature_not_server_content_type() {
        assert_eq!(qr_mime(b"\xff\xd8\xff\xe0test").unwrap(), "image/jpeg");
        assert_eq!(qr_mime(b"\x89PNG\r\n\x1a\ntest").unwrap(), "image/png");
        assert!(qr_mime(b"<html>error</html>").is_err());
    }
}
