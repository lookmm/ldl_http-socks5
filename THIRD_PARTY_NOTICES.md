# 第三方组件与许可声明 / Third-Party Notices

本项目（老戴理 / LDLProxy）整体以 **GPL-3.0** 发布，详见 [LICENSE](LICENSE)。下列第三方组件按各自许可证使用，其版权归原作者所有。

> 生成日期：2026-11-20 · 对应版本：v0.5.11

---

## 1. 运行时二进制依赖

### sing-box（libbox）

- 项目：https://github.com/SagerNet/sing-box
- 用途：Android 侧代理内核，以 `libbox-arm64.aar` 形式在 App 内本地运行（TUN 入站、HTTP/SOCKS5 出站、DNS 与分流）
- 许可证：**GNU GPL v3.0 或更新版本**（GPL-3.0-or-later）
- 版权：Copyright (C) 2022 nekohasekai
- 上游许可声明原文：

  > Copyright (C) 2022 by nekohasekai
  > This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
  > This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
  > In addition, no derivative work may use the name or imply association with this application without prior consent.

- 名称条款处理：本项目未使用 “sing-box” 作为名称、图标或标识，也未暗示与 SagerNet 存在隶属、赞助或背书关系；文档提及仅作事实性依赖说明。
- 二进制分发说明：`libbox-arm64.aar` 未纳入本仓库，需从上游 Releases 获取同版本构建；对应源码即上游仓库，本项目源码见本仓库。
- 许可证全文：https://www.gnu.org/licenses/gpl-3.0.txt

## 2. 规则数据集

| 数据集 | 上游 | 许可证 | 版权 |
| --- | --- | --- | --- |
| `geosite-cn`、`geosite-category-ads-all` | [SagerNet/sing-geosite](https://github.com/SagerNet/sing-geosite) | GPL-3.0 | nekohasekai / SagerNet |
| `geoip-cn` | [SagerNet/sing-geoip](https://github.com/SagerNet/sing-geoip) | GPL-3.0 | nekohasekai / SagerNet |

构建期由上述数据集转换为 sing-box 规则集格式（`.srs`）并作为 assets 打包。本项目以 GPL-3.0 分发，与上游许可证兼容；转换后的规则集同样可按 GPL-3.0 获取（见本仓库 `app/src/main/assets/`）。

## 3. 设计与思路参考（未复用代码）

| 项目 | 参考内容 | 许可证 | 说明 |
| --- | --- | --- | --- |
| [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) | 分流规则顺序、配置结构组织方式 | GPL-3.0 | 仅思路参考，无代码复制 |
| [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) | 内核与 UI 解耦、「减法」式单页配置 | GPL-3.0 | 仅架构参考，无代码复制 |
| [KaringX/karing](https://github.com/KaringX/karing)、SFA | 功能形态与交互细节 | GPL-3.0 + 原名禁用附加条款 | 仅功能参考，无代码复制；不构成衍生作品 |

## 4. Android 与 Kotlin 依赖（Apache-2.0）

- AndroidX Core / Activity / Compose（UI、`lifecycle`、`material3`）
- AndroidX CameraX（`camera-core`、`camera-camera2`、`camera-lifecycle`、`camera-view`）
- Google ZXing Android Embedded（二维码生成 / 识别）
- Kotlin 标准库与协程（Apache-2.0）

许可证：**Apache License 2.0**
版权：Copyright (C) The Android Open Source Project、Google LLC、JetBrains s.r.o.、ZXing authors
许可证全文：https://www.apache.org/licenses/LICENSE-2.0

按 Apache-2.0 §4 要求：保留上述版权、专利、商标与归属声明；如修改其文件需注明；随分发提供许可证副本（本节链接与上述声明即构成 NOTICE 信息）。

## 5. 图形素材

### Twemoji 狗头 emoji

- 文件：`docs/dog-emoji.png`、`app/src/main/res/drawable-xxxhdpi/emoji_dog.png`（U+1F436）
- 来源：https://github.com/jdecked/twemoji
- 许可证：**CC-BY 4.0**（https://creativecommons.org/licenses/by/4.0/）
- 署名：Graphics copyright 2020 Twitter, Inc and other contributors, licensed under CC-BY 4.0.
- 变更说明：仅按原图缩放使用，未修改图形内容。

### 应用图标与截图

由本项目自行绘制/截取，随本项目以 GPL-3.0 授权。

---

## 合规自检清单

- [x] 分发物含 GPL-3.0 代码 → 整体以 GPL-3.0 发布，提供完整对应源码
- [x] 未删除或篡改上游版权声明与许可证文本
- [x] 未使用上游项目名称或暗示官方关联
- [x] 带附加条款的项目仅作参考、未复用代码
- [x] Apache-2.0 组件保留版权与许可声明
- [x] CC-BY 素材完成署名与许可证标注
- [x] 仓库内不含任何真实节点地址、账号与密码
