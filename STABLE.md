# GalMagan 稳定版本

本页记录正式固定的稳定版本，以及各版本相较前一稳定基线新增的功能和完成的重大修复。

## 稳定版本一览

<table>
  <colgroup>
    <col width="8%" />
    <col width="12%" />
    <col width="60%" />
    <col width="20%" />
  </colgroup>
  <thead>
    <tr>
      <th>版本</th>
      <th>标签</th>
      <th>新增功能</th>
      <th>修复</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>2.0.8（当前）</strong></td>
      <td><a href="https://github.com/yu821138652/GalMagan/tree/v2.0.8"><code>v2.0.8</code></a></td>
      <td>相较 <code>1.6.4</code>：<br />• 默认分析改用通用模型，Jev 不再是必需项。<br />• 将分析、回复策略、候选生成和候选检查改为有依赖的顺序流程。<br />• 新增聊天方向选择和本次分析内的多轮补充。<br />• 新增停止分析和悬浮窗刷新操作。<br />• 改进历史预加载顺序锚定、重复过滤及编辑时的位置保持。</td>
      <td>修复悬浮窗使用 <code>TYPE_ACCESSIBILITY_OVERLAY</code> 导致微信无障碍节点树为空的问题；已授予普通悬浮窗权限时改用 <code>TYPE_APPLICATION_OVERLAY</code>。已在荣耀 Magic7、Android 16、微信 8.0.80 验证可以读取聊天消息。详见 <a href="docs/releases/2.0.8.md">2.0.8 版本记录</a>。</td>
    </tr>
    <tr>
      <td><strong>1.6.4</strong></td>
      <td><a href="https://github.com/yu821138652/GalMagan/tree/v1.6.4"><code>v1.6.4</code></a></td>
      <td>相较 <code>1.6.0</code>：<br />• 历史按联系人及聊天环境分别保存，分析时合并同一联系人的跨环境历史。<br />• 预加载可选择联系人与环境，显示进度并支持停止保存。<br />• 小红书优先读取无障碍消息节点，正文缺失时回退 OCR。<br />• 历史消息支持编辑、修正发送方、多选删除，并保持浏览位置。<br />• 全局及面向联系人的说话风格支持合成和手动编辑。</td>
      <td>小红书真机反馈可用；微信仍偶发无法读取，这是 1.6.4 已知限制，后续在 2.0.8 完成对应修复。29 项本地单元测试通过，Debug APK 构建成功。详见 <a href="docs/releases/1.6.4.md">1.6.4 版本记录</a>。</td>
    </tr>
    <tr>
      <td><strong>1.6.0</strong></td>
      <td><a href="https://github.com/yu821138652/GalMagan/tree/v1.6.0"><code>v1.6.0</code></a></td>
      <td>相较前一应用版本 <code>1.5.2</code>（没有独立 Git 标签）：<br />• 完成 GalMagan 品牌图标和应用名称整理。<br />• 重做首页、设置、知识库和联系人编辑页面的视觉层级。<br />• 统一开关、输入框、卡片和按钮样式。<br />• 优化悬浮分析面板、候选卡片和识别状态提示。</td>
      <td>固定应用名称、启动图标和主要页面视觉基线。该版本没有后续历史版本加入的按环境历史、预加载进度和历史批量编辑功能。详见 <a href="docs/releases/1.6.0.md">1.6.0 版本记录</a>。</td>
    </tr>
  </tbody>
</table>

## 后续维护

新功能从 `2.0.8` 继续递增应用版本号。稳定标签和对应内容保持不变，供安装、对照和回退。APK 与校验值见上方版本记录；聊天历史、联系人资料、风格档案和 API 密钥保存在手机应用私有目录，不进入源码仓库。
