import { describe, expect, it } from "vitest";
import { z } from "zod";
import { rollDice, rollDiceInputSchema } from "../roll-dice.js";

describe("rollDice", () => {
  it("每次点数 ∈ [1, sides] 且为整数，数量=count（重复 200 次防侥幸）", () => {
    for (let i = 0; i < 200; i++) {
      const { rolls } = rollDice(6, 3);
      expect(rolls).toHaveLength(3);
      for (const r of rolls) {
        expect(Number.isInteger(r)).toBe(true);
        expect(r).toBeGreaterThanOrEqual(1);
        expect(r).toBeLessThanOrEqual(6);
      }
    }
  });

  it("total 等于各次点数之和", () => {
    const { rolls, total } = rollDice(20, 5);
    expect(total).toBe(rolls.reduce((a, b) => a + b, 0));
  });

  it("默认 6 面 1 次", () => {
    const { rolls } = rollDice();
    expect(rolls).toHaveLength(1);
    expect(rolls[0]).toBeGreaterThanOrEqual(1);
    expect(rolls[0]).toBeLessThanOrEqual(6);
  });
});

describe("rollDiceInputSchema", () => {
  it.each([
    [{ sides: 1 }],
    [{ sides: 1001 }],
    [{ sides: 6.5 }],
    [{ count: 0 }],
    [{ count: 11 }],
  ])("拒绝非法参数 %j", (bad) => {
    expect(z.object(rollDiceInputSchema).safeParse(bad).success).toBe(false);
  });

  it("空对象解析出默认值 sides=6 count=1", () => {
    expect(z.object(rollDiceInputSchema).parse({})).toEqual({ sides: 6, count: 1 });
  });
});
