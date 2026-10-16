import { validatePassword } from '@/utils/password-policy'
import cases from '../../../../src/test/resources/security/password-policy-cases.json'

describe('shared password policy', () => {
  cases.forEach((item, index) => {
    it(`matches backend case ${index}`, () => {
      expect(validatePassword(item.password, item.username)).toBe(item.valid)
    })
  })

  it('accepts every ASCII punctuation character', () => {
    for (let code = 33; code <= 126; code++) {
      const char = String.fromCharCode(code)
      if (!/[a-zA-Z0-9]/.test(char)) {
        expect(validatePassword('Abcdefg12' + char, 'operator')).toBe(true)
      }
    }
  })
})
