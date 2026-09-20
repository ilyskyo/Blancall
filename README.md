> **暂停更新公告**  
> 本仓库即日起停止一切更新活动。  
> **恢复更新日期：不早于 2027 年 6 月 10 日。**

**Blancall**

**Not “blank all”, but “recall”**.Blancall helps you turn any article into fill-in-the-blank exercises. It offers a modern, efficient, and trustworthy way to master the content you need to memorize.

**不是清空，是召回。** Blancall，把文章变填空，帮你用现代又靠谱的方式，真正记住东西。

Blancall 是一个帮助你把任何文章转化为挖空练习的开源项目。基于遗忘曲线调度复习，支持自配 AI 分析练习数据，所有数据本地存储，不上云。

> 提示：本项目的 AI 功能需由用户自行配置第三方 API 服务，请用户自行确保使用第三方 AI API 时遵守其服务条款。

✨ 功能亮点

1.核心背诵

任意文本导入支持粘贴、TXT、PDF 三种方式，自动分句分段。三种挖空模式包括句子挖空、字词挖空和反向默写，覆盖不同记忆层级。跨文复习支持多篇文章混编挖空，打破单篇记忆定势。薄弱集训可以只针对你错过的空或错句集中突击。继续练习允许中途退出随时续上，状态完整保留。

2.记忆科学引擎

基于 FSRS 算法自动排期复习，实现遗忘曲线调度。遗忘预测能够预测每个知识点当前的记忆强度，提示你“该复习了”。记忆热力图和衰减曲线图让复习节奏和记忆强度变化一目了然。难度动态调整根据历史正确率自动调整后续挖空难度。

3.AI 学习助手

自配 API，零订阅费，支持任意 OpenAI 兼容接口（DeepSeek / 通义 / GLM / Kimi 等），填 Key 即用。联网搜索核验让提问自动联网搜索（Tavily），基于最新权威信息回答并附来源。学习数据深度接入让 AI 知道你哪里总错，能针对性出题、分析错误原因。跨文对话和历史续聊支持多篇文章一起问 AI，对话自动存档随时接着聊。

4.隐私与安全

纯本地存储，文章、学习数据、对话记录全部只存在你的设备上，无账号、无云端、无强制上传。API Key 硬件级加密使用 Keystore AES/GCM 加密存储，密钥不进应用代码。完全离线可用，不联网也能完成全部背诵与统计功能。功能零痕迹，关闭开关后所有入口完全消失。

5.学习反馈与激励

全局统计大屏展示练习次数、正确率、各模式分布、趋势一目了然。多维可视化图表包括雷达图、错误分布柱状图、记忆衰减曲线。错误精细分析能对错别字、漏字、多字、顺序错分类统计，精确到字。需加强文章智能推荐自动找出正确率最低的文章，精准补强。成就系统通过练习里程碑给予正反馈。学习分享图一键生成品牌化成绩海报，分享朋友圈。

6.数据导入导出

PDF 导出练习卷可打印，CSV 导出练习记录让数据自由，分享图让成绩海报一张图传播。

注本项目为源代码，不提供apk文件。需自行编译后使用。

环境要求
- Android Studio
- Kotlin
- （其他依赖由 Gradle 自动管理）

克隆与编译

```bash
# 克隆仓库
git clone https://github.com/ilyskyo/Blancall.git

# 用 Android Studio 打开项目
# 等待 Gradle 同步完成
# 点击 Run 按钮编译安装
```

## 开源许可

本项目源代码以 **MIT 许可证** 发布（见仓库根目录 LICENSE 文件）。

> **许可范围说明**：MIT 许可证的商业使用授权适用于本项目源代码；唯一例外是应用内置的手写识别模型权重文件（`app/src/main/assets/hccr`、`hccr_en`），因训练数据授权限制不在商业授权范围内，仅供学习、研究用途（详见下方「手写识别模型」）。

### 字体

应用内嵌字体 **Noto Sans SC**（Google 与 Adobe 联合开发，思源黑体 / Noto Sans CJK 系列），遵循 **SIL Open Font License 1.1** 协议，可免费商用、可随应用捆绑分发。

- 字体主页：https://fonts.google.com/noto
- 开源仓库：https://github.com/notofonts/noto-cjk

应用图标左上角的圆形标识图形使用 **霞鹜新晰黑（LXGW Neo XiHei）** 字体中的字形进行渲染，该字体衍生于「IPAex 黑体」，遵循 **SIL Open Font License 1.1** 与 **IPA Font License 1.0** 协议，可免费商用、可随应用捆绑分发。

- 开源仓库：https://github.com/lxgw/LxgwNeoXiHei

### FSRS 间隔重复算法

本应用采用 **FSRS-6**（Free Spaced Repetition Scheduler）间隔重复算法，移植自 Anki 开源实现 **fsrs-rs**（v6 版本，含 v6.6.0），并参考了 Kotlin 实现 **FSRS-Kotlin**，使用默认参数（默认目标留存率 90%）。

- 论文：Ye, J., Su, J., & Cao, Y. (2022). *A Stochastic Shortest Path Algorithm for Optimizing Spaced Repetition Scheduling*. https://doi.org/10.1145/3534678.3539081
- FSRS-6 算法文档：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
- fsrs-rs 开源仓库（FSRS-6 提供方）：https://github.com/open-spaced-repetition/fsrs-rs
- fsrs-kotlin 开源仓库：https://github.com/open-spaced-repetition/FSRS-Kotlin

### 液态玻璃渲染

应用的液态玻璃（Liquid Glass）视觉效果基于以下开源项目实现：

- **AndroidLiquidGlass**（作者 Kyant0），遵循 **Apache License 2.0** 协议
  开源仓库：https://github.com/Kyant0/AndroidLiquidGlass
- **AndroidLiquidGlassView**（QmDeve），遵循 **MIT License** 协议
  开源仓库：https://github.com/QmDeve/AndroidLiquidGlassView

上述组件的完整许可文本、版权声明及修改情况说明见仓库根目录 [THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES) 文件。

### 手写识别模型

应用内置的本地手写识别模型（NCNN 推理，完全离线运行）由本项目自行训练：

- **中文模型**：7356 类（HWDB 全集：7185 汉字 + 171 字母数字符号），训练数据为 **CASIA-HWDB**（中国科学院自动化研究所发布的手写汉字数据库，仅限非商业学术研究用途）。
- **英文/拉丁模型**：47 类（EMNIST Balanced：数字 + 大小写字母），训练数据为 **EMNIST**（NIST 衍生的公开数据集）。

> **用途声明**：因训练数据 CASIA-HWDB 仅限非商业学术研究用途，本手写识别模型（模型权重文件）不在 MIT 许可证的商业授权范围内，仅供学习、研究用途，不得用于任何商业用途。

## 免责声明

本软件按“现状”提供，不提供任何明示或暗示的担保。作者不对使用本代码造成的任何数据丢失或业务中断承担责任。

## AI 生成软件声明

本软件可能包含由人工智能系统（包括但不限于大语言模型、代码生成工具、自动补全系统及智能编程代理）生成的代码或内容。

声明与责任：
1. AI生成内容：本软件的部分或全部代码可能源自AI模型输出，开发者已在能力范围内进行了审核与整合。
2. 无担保声明：本软件按“现状”（AS IS）提供，不附带任何明示或暗示的担保，包括但不限于适销性、特定用途适用性及不侵权的保证。
3. AI生成的代码可能存在错误、安全漏洞、幻觉逻辑或不完整推理。开发者已尽己所能对代码进行审查与整合，但无法对AI生成内容的完整性、正确性、安全性及特定用途适用性作出任何保证。强烈建议您在使用前进行对代码的审查、测试和安全性评估。
4. 建议：在用于生产环境前，强烈建议进行全面的代码审查和测试。
