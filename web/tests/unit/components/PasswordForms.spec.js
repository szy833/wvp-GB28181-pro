import AddUser from '@/views/user/dialog/addUser.vue'
import ChangePassword from '@/layout/components/dialog/changePassword.vue'
import AdminPassword from '@/views/user/dialog/changePasswordForAdmin.vue'
import ForcePassword from '@/views/user/forceChangePassword.vue'

jest.mock('@/directive/el-drag-dialog', () => ({}))

describe('password form validators', () => {
  const forms = [
    { name: 'add', component: AddUser, field: 'password', model: 'form' },
    { name: 'own', component: ChangePassword, field: 'newPassword', model: 'passwordForm' },
    { name: 'admin', component: AdminPassword, field: 'newPassword', model: 'passwordForm' },
    { name: 'forced', component: ForcePassword, field: 'password', model: 'passwordForm' }
  ]

  forms.forEach(({ name, component, field, model }) => {
    function fixture() {
      const vm = { $store: { state: { user: { name: 'operator' }}}, $refs: { passwordForm: { validateField: jest.fn() }}}
      Object.assign(vm, component.data.call(vm))
      if (vm.form) vm.form.username = 'operator'
      Object.keys(component.computed || {}).forEach(key => {
        Object.defineProperty(vm, key, { get: () => component.computed[key].call(vm) })
      })
      return vm
    }

    it(`${name} rejects weak and username-containing passwords`, () => {
      const vm = fixture()
      for (const value of ['weak', 'Operator12!']) {
        const callback = jest.fn()
        vm.rules[field][0].validator({}, value, callback)
        expect(callback).toHaveBeenCalledWith(expect.any(Error))
      }
    })

    it(`${name} accepts quote and checks confirmation against the actual form model`, () => {
      const vm = fixture()
      vm[model][field] = 'Abcdefg12"'
      vm[model].confirmPassword = 'different'
      const passwordCallback = jest.fn()
      vm.rules[field][0].validator({}, vm[model][field], passwordCallback)
      expect(passwordCallback).toHaveBeenCalledWith()
      const mismatch = jest.fn()
      vm.rules.confirmPassword[0].validator({}, 'different', mismatch)
      expect(mismatch).toHaveBeenCalledWith(expect.any(Error))
      vm[model].confirmPassword = vm[model][field]
      const matches = jest.fn()
      vm.rules.confirmPassword[0].validator({}, vm[model][field], matches)
      expect(matches).toHaveBeenCalledWith()
    })
  })
})
