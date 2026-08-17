/** Reject C0/DEL controls, optionally including the C1 range. */
export function hasControlCharacters(value: string, includeC1 = false): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if (code <= 0x1f || code === 0x7f || (includeC1 && code >= 0x80 && code <= 0x9f)) return true
  }
  return false
}

/** Diagnostics may keep tab/newline/CR for later line-aware stack removal. */
export function hasUnsafeDiagnosticControls(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if ((code <= 0x1f && code !== 0x09 && code !== 0x0a && code !== 0x0d) || code === 0x7f) return true
  }
  return false
}

export function stripUnsafeDiagnosticControls(value: string): string {
  return [...value].filter((character) => !hasUnsafeDiagnosticControls(character)).join('')
}
