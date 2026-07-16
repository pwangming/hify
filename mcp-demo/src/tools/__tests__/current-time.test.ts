import { describe, expect, it } from "vitest";
import { z } from "zod";
import { currentTimeInputSchema, getCurrentTime } from "../current-time.js";

describe("getCurrentTime", () => {
  it("默认时区 Asia/Shanghai，返回含时区名与年份的字符串", () => {
    const s = getCurrentTime();
    expect(s).toContain("Asia/Shanghai");
    expect(s).toMatch(/\d{4}/);
  });

  it("指定 UTC 时区生效", () => {
    expect(getCurrentTime("UTC")).toContain("UTC");
  });

  it("非法时区名抛 RangeError（走工具执行错误路径）", () => {
    expect(() => getCurrentTime("Not/AZone")).toThrow(RangeError);
  });
});

describe("currentTimeInputSchema", () => {
  it("timezone 可选，空参数合法", () => {
    expect(z.object(currentTimeInputSchema).safeParse({}).success).toBe(true);
  });

  it("timezone 必须是字符串", () => {
    expect(z.object(currentTimeInputSchema).safeParse({ timezone: 8 }).success).toBe(false);
  });
});
