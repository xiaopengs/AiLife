export type ThemeFormValues = {
  name: string;
  description: string;
  audienceNeed: string;
};

const themeFieldLabels: Record<keyof ThemeFormValues, string> = {
  name: "主题名称",
  description: "主题说明",
  audienceNeed: "受众核心需求",
};

export function getMissingThemeFields(values: ThemeFormValues): string[] {
  return (Object.keys(themeFieldLabels) as Array<keyof ThemeFormValues>)
    .filter(field => !values[field].trim())
    .map(field => themeFieldLabels[field]);
}
