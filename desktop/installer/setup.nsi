; -*- coding: utf-8 -*-
Unicode true
!include "LogicLib.nsh"
!include "x64.nsh"

!ifndef APP_VERSION
  !error "APP_VERSION must be defined"
!endif
!ifndef STAGE_DIR
  !error "STAGE_DIR must be defined"
!endif
!ifndef OUTPUT_FILE
  !error "OUTPUT_FILE must be defined"
!endif
!ifndef UNINSTALL_INCLUDE
  !error "UNINSTALL_INCLUDE must be defined"
!endif

Name "顺手排"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\Shunshoupai"
InstallDirRegKey HKCU "Software\Shunshoupai" "InstallDir"
RequestExecutionLevel user
ManifestDPIAware true
ManifestLongPathAware true
AllowSkipFiles off
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ShowInstDetails show
AutoCloseWindow true
BrandingText "顺手排 ${APP_VERSION}"
VIProductVersion "${NUMERIC_VERSION}.0"
VIAddVersionKey /LANG=2052 "ProductName" "顺手排"
VIAddVersionKey /LANG=2052 "FileDescription" "顺手排 Windows 安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=2052 "ProductVersion" "${APP_VERSION}"

; The installer starts with installation progress; no configuration wizard.
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles
LoadLanguageFile "${NSISDIR}\Contrib\Language files\SimpChinese.nlf"

Function .onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "顺手排需要 64 位 Windows 10 或 Windows 11。"
    Abort
  ${EndIf}
FunctionEnd

Section "Install"
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  ; If the previous app is running, request that it be closed instead of
  ; deleting a running application or terminating unrelated Node processes.
  SetOverwrite on
  ClearErrors
  File /r "${STAGE_DIR}\*"
  IfErrors 0 +3
    MessageBox MB_OK|MB_ICONSTOP "安装未完成。请关闭正在运行的顺手排，然后重新运行安装程序。"
    Abort
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\顺手排"
  CreateShortCut "$SMPROGRAMS\顺手排\顺手排.lnk" "$INSTDIR\Shunshoupai.exe"
  CreateShortCut "$DESKTOP\顺手排.lnk" "$INSTDIR\Shunshoupai.exe"
  WriteRegStr HKCU "Software\Shunshoupai" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "DisplayName" "顺手排"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "Publisher" "G-E-E-D"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai" "NoRepair" 1
SectionEnd

Function .onInstSuccess
  IfSilent done
  Exec '"$INSTDIR\Shunshoupai.exe"'
done:
FunctionEnd

Section "Uninstall"
  SetShellVarContext current
  Delete "$DESKTOP\顺手排.lnk"
  Delete "$SMPROGRAMS\顺手排\顺手排.lnk"
  RMDir "$SMPROGRAMS\顺手排"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shunshoupai"
  DeleteRegKey HKCU "Software\Shunshoupai"
  ; INSTDIR only contains installed program files. Account/database/key files
  ; in $LOCALAPPDATA\Shunshoupai are deliberately preserved during uninstall.
  !include "${UNINSTALL_INCLUDE}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
SectionEnd
