import { z } from "zod";

/** zod raw shape：registerTool 的 inputSchema 直接吃这个形状。 */
export const currentTimeInputSchema = {
  timezone: z
    .string()
    .describe("IANA 时区名，如 Asia/Shanghai、America/New_York；不传默认 Asia/Shanghai")
    .optional(),
};

export function getCurrentTime(timezone = "Asia/Shanghai"): string {
  // 非法时区名 Intl 会抛 RangeError，由协议层转成 isError 结果
  const fmt = new Intl.DateTimeFormat("zh-CN", {
    timeZone: timezone,
    dateStyle: "full",
    timeStyle: "medium",
  });
  return `${timezone} 当前时间：${fmt.format(new Date())}`;
}
