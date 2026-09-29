# Tasks

## 1. ReAct 循环

- [ ] 1.1 校验 `ReActAgent.run` 按全历史回喂驱动循环：mock 脚本依次查天气→荐景点→Finish 即可终止；运行 `java -cp out agent.Main mock "推荐一下北京"` 并确认三阶段输出后打印最终答案
- [ ] 1.2 校验 `maxRounds` 兜底：构造始终不 Finish 的脚本，确认跑满轮数后返回兜底提示而非死循环（运行同命令观察轮数与提示）

## 2. 工具抽象与派发

- [ ] 2.1 确认 `Tool` 接口 + `ToolRegistry`（按名查、`describeTools` 拼进系统提示词）与 spec"按名执行/未知工具引导重试"一致；用 mock 触发一个未注册工具名，确认返回"工具不存在"提示
- [ ] 2.2 验证 `ActionParser` 解析 `get_weather(city="北京")` 与 `Finish[...]` 两种形态；写一个解析结果为 null 的输入，确认上层不崩溃并继续

## 3. 天气与景点工具

- [ ] 3.1 校验 `WeatherTool`（wttr.in `?format=j1`）：对有效城市返回"`{城市}当前天气:{描述}，气温{温度}摄氏度"；对无效城市/网络失败返回"错误:"开头的说明而不抛异常（可用缺省或断网路径验证）
- [ ] 3.2 校验 `AttractionTool`（Tavily `/search`）：不配 `TAVILY_API_KEY` 返回"错误:未配置..."；配 key 后优先取 `answer`，否则逐条格式化 `results`——至少确认未配 key 分支

## 4. LLM 客户端与 Mock 模式

- [ ] 4.1 确认 `OpenAiCompatibleChatClient` 按 OpenAI 兼容接口发 POST、读 `choices[0].message.content`；未配 `LLM_API_KEY` 时抛出"未配置"错误提示改用 mock
- [ ] 4.2 确认 `MockChatClient` 离线路径：无任何环境变量下 `java -cp out agent.Main mock` 完整走通查天气→荐景点→Finish，全程无真实网络调用

## 5. 入口与集成

- [ ] 5.1 确认 `Main` 在无 `LLM_API_KEY` 时自动回退 Mock、配 key 时走真实客户端；`run-mock.sh` 编译并跑通一次输出"最终答案"