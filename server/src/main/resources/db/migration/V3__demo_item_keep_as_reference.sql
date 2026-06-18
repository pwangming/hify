-- V3：demo_item 由「临时验证表」转正为「学习参考表（长期保留）」。
-- 仅更新表注释，不动结构与数据。背景见 code-organization.md 第 1 节「学习参考模块」与 com.hify.demo 包说明。
-- 注：V2 已执行，按规范禁止回改旧脚本，故注释变更通过本新脚本落地。
comment on table demo_item is '学习参考表：演示标准 CRUD 链路（长期保留，非业务表）';
