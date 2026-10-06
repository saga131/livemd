# LiveMD — 自托管 LiveSync 笔记阅读器

一个轻量的开源 Android 应用，直连 [Self-hosted LiveSync](https://github.com/vrtmrz/obsidian-livesync)
所使用的 CouchDB 服务器，**只读**地拉取并阅读 Markdown 笔记。
服务器地址、数据库、账号密码、E2EE 密码短语全部在 App 内填写，无需改代码。

> 本项目是独立实现，与 Obsidian / Dynalist Inc. 及 Self-hosted LiveSync 作者无关。
> "Obsidian" 是 Dynalist Inc. 的商标，仅作描述性使用。

## 功能

- 轻量拉取：Mango 查询只取文本笔记元数据 + `_bulk_get` 只下载对应分块
- **图片支持**：解析笔记里的 `![[xx.png]]` 和 `![alt](path)` 引用，只下载被引用的图片，
  逐张下载（内存有界），按屏宽降采样内嵌渲染；`children` 分块 ID 即内容指纹，
  增量同步时未变更的图片直接跳过
- **选择性同步**：设置 → 选择同步范围，按顶层文件夹勾选（含子文件夹），
  范围外的笔记连分块都不下载；低端小内存设备建议只勾常用文件夹
- 平板双栏布局：最小宽度 ≥600dp 自动切换「左列表 + 右阅读」，
  选中高亮，阅读列宽 860dp 上限；手机保持单栏
- 实时更新：前台期间长轮询 `_changes`（带游标），其他设备写入后自动拉取刷新
- 阅读：大纲跳转（标题层级列表）、文内查找（全高亮 + 循环跳转）
- 搜索：标题/路径 + 全文（对本地缓存检索，离线可用）
- 兼容 Self-hosted LiveSync 三代存储格式：
  - **V2 / HKDF**（当前默认，`%=` 前缀）：PBKDF2-SHA256 31 万次迭代 → HKDF-SHA256 → AES-256-GCM
  - **V1 旧格式**（`%` 前缀）：动态迭代次数 + 固定 10 万次自动回退
  - **明文**（未开启 E2EE）
- 混淆路径（`f:` 前缀）与 HKDF 加密元数据（`/\:` 前缀）自动识别解密
- Markdown 渲染：标题、列表、引用、代码块、行内代码、粗斜体、删除线、高亮、
  链接、`[[wikilink]]`、表格、分隔线（零第三方依赖的 Spannable 实现）
- 服务器上已删除的文件，本地缓存同步清理

## 编译

```bash
# 需要 JDK 17+ 与 Android SDK (compileSdk 34)
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开本目录。

## 发新版

打 tag 推送即可，GitHub Actions 会自动构建并发布 Release（无需本地构建）：

```bash
python release.py 0.2.0   # 自动提交变更 → 打 tag v0.2.0 → 推送 → 云端出包
```

进度见仓库 Actions 页，产物自动挂到 Releases。

## 使用

1. 先在 Obsidian（桌面端）安装 Self-hosted LiveSync 插件，按其官方文档
   搭建好 CouchDB 服务端（Docker 一条命令即可，见插件文档 Setup Own Server）
2. 安装本 APK，打开后点菜单「设置」
3. 填写：
   - 服务器地址：如 `https://your-server.com`（或 `http://IP:5984`）
   - 数据库名：LiveSync 插件里配的那个（小写）
   - 用户名 / 密码：CouchDB 凭据
   - E2EE 密码短语：LiveSync 插件里配置的加密口令（未开启加密则留空）
4. 「测试连接」确认 →「保存」→ 主界面右上角「同步」
5. 低端设备：设置 →「选择同步范围」，只勾选需要的文件夹

## 技术说明

存储格式通过阅读 Self-hosted LiveSync 与 octagonal-wheels（均为 MIT 协议）
的开源实现整理而来，本仓库代码为 Kotlin 独立实现，未复制其源码；
解密算法经过与官方实现的交叉验证（官方加密 ↔ 本实现解密，含中文/emoji 用例）。

- 每个 Obsidian 文件 = 1 条元数据文档（`type` ∈ `plain`/`newnote`/`notes`，含 `children` 分块 ID 列表）
  + 多条内容寻址分块文档（`_id` 形如 `h:xxx`，`type` = `leaf`）
- E2EE 开启时分块 `data` 加密（`e_: true`），PBKDF2 salt 存于
  `_local/obsidian_livesync_sync_parameters` 文档（base64）
- `newnote` 的分块内容是逐块 base64，需逐块解码后按字节拼接；`plain` 直接拼接文本

## 已知限制

- 只读：编辑/回写需要实现 LiveSync 的复制协议（冲突处理），尚未开发
- 图片仅同步"被已同步笔记引用"的那些；PDF/音频等其他附件类型不显示
- 大量图片的笔记首次同步较慢（弱带宽下逐张下载，之后按指纹增量跳过）
- `chunkpack` 打包分块（实验特性）暂不解析，遇到时该文件会跳过
- V3 加密（`%~` 前缀）暂不支持

## 许可

MIT，见 [LICENSE](LICENSE)。
