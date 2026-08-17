import { hasControlCharacters } from '../state/textSafety'

const MAX_DIRECTORY_NAME_BYTES = 255

export type DirectoryNameValidation =
  | { ok: true; value: string }
  | { ok: false; message: string }

/** Match and strengthen the existing host.createDirectory segment contract. */
export function validateDirectoryName(value: string): DirectoryNameValidation {
  if (value.length === 0 || value.trim().length === 0) {
    return { ok: false, message: '目录名不能为空' }
  }
  if (value !== value.trim()) {
    return { ok: false, message: '目录名首尾不能包含空白字符' }
  }
  if (value === '.' || value === '..') {
    return { ok: false, message: '目录名不能是 . 或 ..' }
  }
  if (/[\\/]/.test(value)) {
    return { ok: false, message: '目录名不能包含斜杠或反斜杠' }
  }
  if (hasControlCharacters(value, true)) {
    return { ok: false, message: '目录名不能包含控制字符' }
  }
  if ([...value].length > MAX_DIRECTORY_NAME_BYTES || new TextEncoder().encode(value).length > MAX_DIRECTORY_NAME_BYTES) {
    return { ok: false, message: '目录名不能超过 255 字节' }
  }
  return { ok: true, value }
}
