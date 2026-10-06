"""LiveMD 发布助手。

用法: python release.py <版本号>   例如: python release.py 0.2.0

流程:
1. 若有未提交变更 → 自动提交
2. 打 tag v<版本号>
3. 推送 main + tag 到 GitHub（自动重试直连被重置）
4. GitHub Actions 云端构建 APK 并发布 Release（本脚本只打印进度链接）
"""
import subprocess, sys, time, urllib.request

REPO = "saga131/livemd"


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


def push_with_retry(cmd, tries=5):
    """github.com:443 偶发被重置，间隔重试；代理 7897 挂着时也照样能推（绕过代理直连）"""
    for i in range(1, tries + 1):
        code, out = run(["git", "-c", "http.proxy=", "-c", "https.proxy="] + cmd)
        if code == 0:
            print(out)
            return True
        print(f"  推送失败({i}/{tries}): {out[-120:]}")
        if i < tries:
            time.sleep(20)
    return False


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    ver = sys.argv[1].lstrip("v")
    tag = f"v{ver}"
    print(f"== 发布 LiveMD {tag} ==")

    # 1. 自动提交变更
    _, st = run(["git", "status", "--porcelain"])
    if st.strip():
        run(["git", "add", "-A"])
        code, out = run(["git", "commit", "-m", f"release: {tag}"])
        if code != 0:
            print(out); sys.exit(1)
        print("已提交本地变更")
    else:
        print("工作区干净，无需提交")

    # 2. 打 tag
    run(["git", "tag", "-f", tag])
    print(f"tag {tag} 就绪")

    # 3. 推送（先 main 后 tag，重试）
    if not push_with_retry(["push", "origin", "main"]):
        sys.exit("main 推送失败，请检查网络后重跑")
    if not push_with_retry(["push", "origin", tag]):
        sys.exit("tag 推送失败，请检查网络后重跑")

    # 4. 提示进度
    print(f"""
== 代码已推送，GitHub Actions 正在云端构建（约 3-5 分钟）==
进度: https://github.com/{REPO}/actions
完成后 Release 自动出现在: https://github.com/{REPO}/releases/tag/{tag}
APK 直链: https://github.com/{REPO}/releases/download/{tag}/LiveMD-{ver}.apk
""")


if __name__ == "__main__":
    main()
