// Keep this policy aligned with PasswordComplexityValidator and the shared test vectors.
export const passwordPolicyMessage = '密码至少10位，包含大写字母、小写字母、数字和特殊符号，仅允许可见ASCII字符（不含空格），且不能包含用户名'

export function validatePassword(password, username) {
  if (typeof password !== 'string' || typeof username !== 'string' || !username) {
    return false
  }
  return password.length >= 10 && !/[^\x21-\x7E]/.test(password) &&
    /[a-z]/.test(password) && /[A-Z]/.test(password) && /[0-9]/.test(password) &&
    /[\x21-\x2F\x3A-\x40\x5B-\x60\x7B-\x7E]/.test(password) &&
    !password.toLowerCase().includes(username.toLowerCase())
}
