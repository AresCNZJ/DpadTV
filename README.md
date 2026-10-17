# DpadTV

[LibreTV](https://github.com/LibreSpark/LibreTV) 的 Android TV 客户端 —— 原生实现，不依赖 WebView。

对接你自建的 LibreTV 服务端，在电视上用遥控器浏览、搜索、播放。

> 非官方项目，与 LibreTV 作者无隶属关系。



## 功能

- **片源管理**：粘贴 sources.json / 选设备文件 / 内网网址直连 / 订阅地址 / 手动添加 / **手机扫码添加**；每条可启用停用、改名称地址、上下移动、测试连通性、删除
- **首页**：豆瓣热门海报网格，电影 / 剧集切换，分批加载
- **搜索**：多源聚合，按片名分组显示来源数，可按来源筛选，记住搜索历史
- **详情**：封面、简介、集数、一键换源（当前源标 ●）
- **播放**：HLS、续播、进度条、缓冲提示、自动连播下一集、解码失败明确提示
- **直播**：M3U 多源、按分组筛选
- **继续观看**：本地观看历史（封面 + 进度），长按删除

## 截图

### 首页

![首页](docs/screenshot-home.png)

### 详情

![详情](docs/screenshot-detail.png)

### 扫码添加片源

![扫码](docs/screenshot-qr.png)

## 安装

到 [Releases](../../releases) 下载 APK，拷到电视安装（需允许"未知来源"）。

要求：Android 6.0+，任意 CPU 架构（包内没有原生库）。

## 首次配置

1. 填服务器地址与访问密码，点「连接并登录」
2. 进「片源管理」添加采集站
3. 回首页搜片名

> 片源只支持 Apple CMS **JSON** 接口（`https://xxx/api.php/provide/vod`），`/at/xml/` 这类 XML 通道不支持。

## 构建

```bash
# JDK 17+
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
```

可选：把默认服务器地址 / 密码写进 `local.properties`（不入库），构建时注入：

```properties
dpadtv.defaultUrl=http://192.168.1.10:8899
dpadtv.defaultPassword=your-password
```

不配置也可以，用户首次启动时自己填。

## 自测（不需要真机）

```bash
node tools/e2e-api.mjs --url http://你的NAS:8899 --password 你的密码
```

复现客户端的调用序列（登录 → 搜索 → 详情 → 播放地址），并**真的拉取 m3u8** 验证地址可播。

## 已知限制

- **H.265 片源**能否播取决于 SoC 硬解，不支持时会明确提示"解码失败"
- 直播只支持 HLS / TS / 渐进式，**FLV 不支持**（ExoPlayer 没有 FLV 解封装器）
- 直播 EPG 未实现
- 观看历史 / 搜索历史都是**本机**的，多设备不同步
- 画面比例、音轨切换、字幕选择尚未做

## 支持作者

如果这个客户端帮你省了时间，可以请我喝杯咖啡 —— **不影响任何功能**。

| 微信 | 支付宝 |
|---|---|
| <img src="docs/donate-wechat.png" width="200"> | <img src="docs/donate-alipay.png" width="200"> |

## 免责声明

本项目仅为**客户端**，不提供、不存储、不分发任何音视频内容。所有内容均来自用户自行配置的第三方接口。
请自行确认内容来源的合法性，使用者需自行承担相应责任。

## 名称与标识

代码本身遵循 GPL-3.0，修改和再分发都没有附加条件。

只请求一件事：二次发行时换掉应用名称、图标和包名（`com.dpadtv.client`），并注明是非官方版本。
这样别人不会把改动过的版本误认成原版，对项目和你都更省事。

> "DpadTV" 名称与图标本身不随 GPL-3.0 授权。
## 许可

[GNU General Public License v3.0](LICENSE)

可以自由使用、修改、分发；但分发修改后的版本时必须同样以 GPL-3.0 开源，且不能使用本项目的名称与图标（见上方「名称与标识」）。

## 致谢

- [LibreTV](https://github.com/LibreSpark/LibreTV) —— 服务端与接口设计
- [AndroidX Media3](https://github.com/androidx/media) —— 播放器
- [ZXing](https://github.com/zxing/zxing) —— 二维码生成