# ADR 0002：以不可变活动片段支持准确报表

仅保存 Session 累计时长无法判断暂停发生在何时，也无法把跨午夜任务准确拆分到不同日期。Schema v1 因此增加 `session_segments`：每次开始或恢复打开一个 WARMUP/FOCUS 片段，暂停、阶段转换和结束时关闭。Session 仍保存累计值用于快速列表，报表以片段为准确来源。

该表是内部事实记录；公开导出仍以稳定 Session 格式为主，后续 Schema 可以选择附带片段以支持完整重建。

Session 同时保存 `taskNameSnapshot`，使模板后续改名或归档时，历史记录仍能准确展示当时的任务名称。
