@echo off
rem LiveMD 发布助手：python release.py <版本号，如 0.2.0>
rem 流程：自动提交变更 → 打 tag → 推送 → GitHub Actions 云端构建并发布 Release
python "%~dp0release.py" %*
