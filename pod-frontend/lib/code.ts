// Rules for Code steps' settings, the same as pod-backend's StepSettingsValidator (and
// pod-processor's CodeHandler), so problems show while typing rather than on save.

// Groovy's keywords and the names the script runner uses.
const RESERVED = new Set([
  "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
  "def", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float", "for",
  "goto", "if", "implements", "import", "in", "instanceof", "int", "interface", "long", "native", "new", "null",
  "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
  "synchronized", "this", "threadsafe", "throw", "throws", "trait", "transient", "true", "try", "var", "void",
  "volatile", "while", "yield", "record", "sealed", "permits", "non", "it", "out", "binding",
]);

// Why a name can't be a script variable, or null when it can.
export function variableProblem(name: string): string | null {
  if (!name.trim()) return null;
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name.trim())) return "Use letters, digits and _, not starting with a digit";
  if (RESERVED.has(name.trim())) return "Groovy reserves this word";
  return null;
}

// Why a name can't be an output's, or null when it can. `taken`: names the step already has.
export function outputKeyProblem(key: string, taken: string[]): string | null {
  if (!key.trim()) return "Give it a name";
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key.trim())) return "Use letters, digits and _, starting with a letter";
  if (taken.includes(key.trim())) return "Already used";
  return null;
}
