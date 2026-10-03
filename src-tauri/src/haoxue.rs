//! 好学课堂实录登录窗口。学校把令牌交给页面里的原生桥，本模块只负责注入桥、
//! 把原始载荷交给核心解析，并在校验通过后关闭窗口。密码始终只留在学校页面里。
use super::*;
use tauri::Emitter;

const LABEL: &str = "haoxue-login";
const LOGIN: &str = "https://passport.pku.edu.cn/auth/login?redirect=https%3A%2F%2Fpassport.pku.edu.cn%2Fauth%2Fapp-login";
const RELAY: &str = "https://passport.pku.edu.cn/auth/app-login";
/// 令牌中转页只对安卓 WebView 身份调用原生桥，与好学安卓客户端的行为一致。
const ANDROID_UA: &str = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36";

/// 桥接约定：页面调用 `bridge.PKULoginSuccess(payload)`。桌面身份下中转页不会调用
/// 桥，注入脚本发现停在 relay 页就请求以安卓身份重开该页；relay 窗口不再重试。
const BRIDGE: &str = r#"(function () {
  // 只有中转页会把令牌交给原生桥；其余页面一概不注入，
  // 不碰学校页面自己的全局对象与 sessionStorage。
  var relay = location.href.indexOf('https://passport.pku.edu.cn/auth/app-login') === 0;
  if (!relay) return;
  var internals = window.__TAURI_INTERNALS__;
  if (!internals || !internals.invoke) return;
  var settled = false;
  var bridge = {
    PKULoginSuccess: function (payload) {
      settled = true;
      internals.invoke('haoxue_login', { payload: String(payload) }).catch(function () {});
    },
  };
  try {
    Object.defineProperty(window, 'bridge', {
      configurable: false,
      enumerable: true,
      get: function () { return bridge; },
      set: function () {},
    });
  } catch (error) {
    window.bridge = bridge;
  }
  var retried = sessionStorage.getItem('onpku-relay-retry') === '1';
  if (!retried && !window.__onpkuAndroid) {
    sessionStorage.setItem('onpku-relay-retry', '1');
    setTimeout(function () {
      if (!settled) internals.invoke('haoxue_login_retry', {}).catch(function () {});
    }, 1500);
  }
})();"#;

/// 认证页会把登录交给学校统一身份认证，两个域都要放行，否则跳转被拦下就是一片空白。
/// 只有 passport 的中转页会把令牌交给原生桥，能力配置里的 remote.urls 仍只放 passport。
fn allowed_navigation(url: &tauri::Url) -> bool {
    // about:blank 必须放行：WebView2 在建窗口与重开时会先经过空白文档，
    // 拦掉它整个页面就再也起不来（浏览器原文窗口曾因此白屏）。
    if url.as_str() == "about:blank" {
        return true;
    }
    ["https://passport.pku.edu.cn/", "https://iaaa.pku.edu.cn/"]
        .iter()
        .any(|prefix| url.as_str().starts_with(prefix))
}

fn is_login_window(label: &str) -> bool {
    label.starts_with(LABEL)
}

static NEXT_LOGIN: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(1);

fn open(app: &tauri::AppHandle, target: &str, android: bool) -> Result<(), String> {
    // 新窗口用轮换标签先建起来，再关旧的：同名标签「关了马上建」会和还没走完的
    // 关闭流程撞车，建不出来就把人留在白屏里。
    let label = format!(
        "{LABEL}-{}",
        NEXT_LOGIN.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    );
    let mut builder = tauri::WebviewWindowBuilder::new(
        app,
        &label,
        tauri::WebviewUrl::External(target.parse().map_err(|_| "登录地址无效")?),
    )
    .title("课堂实录 · 统一身份认证")
    .inner_size(980.0, 760.0)
    .min_inner_size(620.0, 520.0)
    // 桥必须在地道脚本之前注入，否则中转页找不到 `bridge.PKULoginSuccess`。
    .initialization_script(BRIDGE)
    .on_navigation(allowed_navigation)
    .on_page_load(|window, payload| {
        if payload.event() != tauri::webview::PageLoadEvent::Finished {
            return;
        }
        if !allowed_navigation(payload.url()) || payload.url().as_str() == "about:blank" {
            return;
        }
        let _ = window.eval(
            r#"setTimeout(function () {
              var body = document.body;
              var blank = !body || body.innerText.replace(/\s+/g, '').length === 0;
              if (!blank) return;
              if (sessionStorage.getItem('onpku-blank-reload') === '1') return;
              sessionStorage.setItem('onpku-blank-reload', '1');
              location.reload();
            }, 3000);"#,
        );
    });
    if android {
        builder = builder.user_agent(ANDROID_UA);
    }
    // 安卓身份下仍要先注入桥，否则中转页找不到原生接口。
    let window = builder.build().map_err(|_| "无法打开课堂实录登录窗口")?;
    for (old_label, old) in app.webview_windows() {
        if old_label != label && is_login_window(&old_label) {
            let _ = old.close();
        }
    }
    if android {
        let _ = window.eval("window.__onpkuAndroid = true;");
    }
    window.set_focus().map_err(|_| "登录窗口无法激活")?;
    Ok(())
}

/// 必须是 async：同步命令在主线程执行，而建窗口又要等主线程，直接把自己锁死。
#[tauri::command]
pub(crate) async fn open_haoxue_login(window: tauri::WebviewWindow) -> Result<(), String> {
    if window.label() != "main" {
        return Err("此窗口不可执行本地操作".into());
    }
    open(&window.app_handle().clone(), LOGIN, false)
}

/// 中转页在桌面身份下不调用桥时，改用安卓 WebView 身份重开同一页。
#[tauri::command]
pub(crate) async fn haoxue_login_retry(window: tauri::WebviewWindow) -> Result<(), String> {
    if !is_login_window(window.label()) {
        return Err("此窗口不可更改登录方式".into());
    }
    open(&window.app_handle().clone(), RELAY, true)
}

/// 只有本窗口的载荷可以写入会话；载荷原样交给核心解析与保存。
#[tauri::command]
pub(crate) async fn haoxue_login(
    window: tauri::WebviewWindow,
    state: tauri::State<'_, Arc<campus_core::Core>>,
    payload: String,
) -> Result<(), String> {
    if !is_login_window(window.label()) {
        return Err("此窗口不可写入登录状态".into());
    }
    let core = state.inner().clone();
    let parsed = serde_json::from_str(&payload).unwrap_or(serde_json::Value::String(payload));
    let envelope = tauri::async_runtime::spawn_blocking(move || {
        core.call(campus_core::Request::HaoxueLogin { payload: parsed })
    })
    .await
    .map_err(|_| "服务暂不可用".to_string())?;
    if envelope.error.is_some() {
        return Err(
            envelope.error
                .map(|problem| problem.message)
                .unwrap_or_else(|| "登录未完成".to_string()),
        );
    }
    let _ = window.close();
    // 登录窗口是自己关的，主界面不会知道：广播一次，让回放列表马上刷新。
    let _ = window
        .app_handle()
        .emit("haoxue-connected", ())
        .map_err(|_| ());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn login_window_labels_rotate_but_stay_recognizable() {
        assert!(is_login_window("haoxue-login-3"));
        assert!(is_login_window("haoxue-login"));
        assert!(!is_login_window("reader-2"));
        assert!(!is_login_window("main"));
    }
    #[test]
    fn the_login_window_follows_the_unified_auth_redirect() {
        let ok = [
            "https://passport.pku.edu.cn/auth/login?redirect=x",
            "https://iaaa.pku.edu.cn/iaaa/oauth.jsp",
            "about:blank",
        ];
        let blocked = [
            "https://evil.test/auth/login",
            "https://passport.pku.edu.cn.evil.test/",
            "http://passport.pku.edu.cn/auth/login",
            "https://example.com/",
        ];
        for url in ok {
            assert!(allowed_navigation(&url.parse().unwrap()), "{url}");
        }
        for url in blocked {
            assert!(!allowed_navigation(&url.parse().unwrap()), "{url}");
        }
    }
}
