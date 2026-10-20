; OnePKU 卸载钩子：由 src-tauri/tauri.windows.conf.json 的 installerHooks 引入。
;
; Tauri 自带的 NSIS 模板只清 identifier 命名的两个目录
; （%APPDATA%\me.petertian.onepku 与 %LOCALAPPDATA%\me.petertian.onepku，后者就是 WebView2 的
; EBWebView）。而 Rust 核心用 directories 写到 %LOCALAPPDATA%\petertian\OnePKU 与
; %APPDATA%\petertian\OnePKU，学校会话与 Cookie 又落在与 PKU CLI 共用的 %APPDATA%\info，
; 凭据在 Windows 凭据管理器里，这些模板都够不到，只能在这里补。
;
; 静默/passive 卸载会跳过确认页，$DeleteAppDataCheckboxState 保持 0，等于不动任何数据。

!include "LogicLib.nsh"

LangString onepkuDownloadsQuestion ${LANG_SIMPCHINESE} "是否连「下载\OnePKU」里的课件与回放文件一起删除？$\n$\n这些文件不进回收站，删掉就无法恢复。"
LangString onepkuDownloadsQuestion ${LANG_ENGLISH} "Also delete the course files and recordings in Downloads\OnePKU?$\n$\nThey are not moved to the Recycle Bin and cannot be recovered."

!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    SetShellVarContext current

    ; 缓存与业务数据：资源快照、培养方案 PDF、回放分片、字幕、锁文件、作业暂存
    RMDir /r "$LOCALAPPDATA\petertian\OnePKU"
    ; 偏好与提醒基线
    RMDir /r "$APPDATA\petertian\OnePKU"
    ; 学校会话与 Cookie。这个目录与 pkucli 命令行共用，删了终端里要重新登录
    RMDir /r "$APPDATA\info"
    ; iAAA 凭据：keyring 在 Windows 上的目标名是 <user>.<service>
    nsExec::Exec 'cmdkey /generic:username.info-pku /delete'
    nsExec::Exec 'cmdkey /generic:password.info-pku /delete'
    ; 应用内更新留下的安装器目录：<应用名>-<版本>-updater-<随机>，插件用的是 tempdir().keep()
    !insertmacro RemoveUpdaterTemp

    ; 下载的资料属于用户内容，单独确认，默认不删。
    ; Downloads 的真实位置从注册表读：NSIS 的 $DOWNLOADS 常量并非所有版本都有，
    ; 而且用户可能把「下载」整个重定向到其他盘——注册表这个键就是系统给出的权威位置。
    MessageBox MB_YESNO|MB_ICONEXCLAMATION "$(onepkuDownloadsQuestion)" IDNO onepku_keep_downloads
      ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" "{374DE290-123F-4555-916C-4DA4E70E65B8}"
      ${If} $0 == ""
        StrCpy $0 "$PROFILE\Downloads"
      ${EndIf}
      ExpandEnvStrings $0 $0
      RMDir /r "$0\OnePKU"
    onepku_keep_downloads:
  ${EndIf}
!macroend

!macro RemoveUpdaterTemp
  FindFirst $0 $1 "$TEMP\OnePKU-*-updater-*"
  onepku_updater_scan:
    StrCmp $1 "" onepku_updater_done
    IfFileExists "$TEMP\$1\*.*" 0 onepku_updater_next
      RMDir /r "$TEMP\$1"
    onepku_updater_next:
    FindNext $0 $1
    Goto onepku_updater_scan
  onepku_updater_done:
  FindClose $0
!macroend
