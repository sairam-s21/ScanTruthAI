export type HealthCategory = "condition" | "allergy" | "medication" | "dietary";

export type ProfileListField = "conditions" | "allergies" | "medications" | "dietaryRestrictions";

export const HEALTH_CATEGORIES: Record<
  HealthCategory,
  { field: ProfileListField; singular: string; addTitle: string; placeholder: string }
> = {
  condition: {
    field: "conditions",
    singular: "medical condition",
    addTitle: "Add a condition",
    placeholder: "e.g. Hypertension",
  },
  allergy: {
    field: "allergies",
    singular: "allergy",
    addTitle: "Add allergy",
    placeholder: "e.g. Peanuts",
  },
  medication: {
    field: "medications",
    singular: "medication",
    addTitle: "Add medication",
    placeholder: "e.g. Metformin 500 mg",
  },
  dietary: {
    field: "dietaryRestrictions",
    singular: "dietary restriction",
    addTitle: "Add dietary restriction",
    placeholder: "e.g. Low sodium",
  },
};

/** Cheap check before any model call: 2-60 chars, at least two letters, no unusual symbols. */
export function passesBasicCheck(text: string): boolean {
  if (text.length < 2 || text.length > 60) return false;
  if ((text.match(/\p{L}/gu) || []).length < 2) return false;
  return /^[\p{L}\p{N} .,\-()%'+/&]+$/u.test(text);
}

/** Appends new items, skipping blanks and case-insensitive duplicates, keeping order. */
export function unionItems(existing: string[] = [], incoming: string[] = []): string[] {
  const result = [...existing];
  for (const raw of incoming) {
    const item = raw.trim();
    if (item && !result.some((e) => e.toLowerCase() === item.toLowerCase())) result.push(item);
  }
  return result;
}
