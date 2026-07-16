import { z } from "zod";

/** zod raw shape：带默认值，协议层 parse 后处理器拿到的一定是数字。 */
export const rollDiceInputSchema = {
  sides: z.number().int().min(2).max(1000).default(6).describe("骰子面数，2-1000，默认 6"),
  count: z.number().int().min(1).max(10).default(1).describe("掷几次，1-10，默认 1"),
};

export function rollDice(sides = 6, count = 1): { rolls: number[]; total: number } {
  const rolls = Array.from({ length: count }, () => 1 + Math.floor(Math.random() * sides));
  const total = rolls.reduce((a, b) => a + b, 0);
  return { rolls, total };
}
