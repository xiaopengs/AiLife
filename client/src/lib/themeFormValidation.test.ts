import { describe, expect, it } from "vitest";
import { getMissingThemeFields } from "./themeFormValidation";

describe("getMissingThemeFields", () => {
  it("lists every required field that only contains whitespace", () => {
    expect(getMissingThemeFields({ name: "  ", description: "说明", audienceNeed: "" })).toEqual(["主题名称", "受众核心需求"]);
  });

  it("allows a complete skill theme form", () => {
    expect(getMissingThemeFields({ name: "游戏开发", description: "围绕创意原型与工具链", audienceNeed: "提升制作效率" })).toEqual([]);
  });
});
