import userModule from '@/store/modules/user'
import * as api from '@/api/user'
import * as auth from '@/utils/auth'
import { resetRouter } from '@/router'

jest.mock('@/api/user', () => ({
  login: jest.fn(), logout: jest.fn(), getUserInfo: jest.fn(), changePassword: jest.fn()
}))
jest.mock('@/utils/auth', () => ({
  getToken: jest.fn(), getDefaultPassword: jest.fn(), setToken: jest.fn(), setName: jest.fn(),
  setServerId: jest.fn(), setDefaultPassword: jest.fn(), removeToken: jest.fn(), removeName: jest.fn(),
  removeServerId: jest.fn(), removeDefaultPassword: jest.fn()
}))
jest.mock('@/router', () => ({ resetRouter: jest.fn() }))

describe('default password user state', () => {
  let state
  let commit
  beforeEach(() => {
    jest.clearAllMocks()
    state = { defaultPassword: true, identityLoaded: false }
    commit = jest.fn((type, value) => userModule.mutations[type](state, value))
  })

  it('records the server flag on login', async() => {
    api.login.mockResolvedValue({ data: { accessToken: 'token', username: 'admin', defaultPassword: true }})
    await userModule.actions.login({ commit }, { username: 'admin', password: 'admin' })
    expect(state.defaultPassword).toBe(true)
    expect(state.identityLoaded).toBe(true)
    expect(auth.setDefaultPassword).toHaveBeenCalledWith(true)
  })

  it('refreshes flag and username from identity endpoint', async() => {
    api.getUserInfo.mockResolvedValue({ data: { username: 'admin', defaultPassword: true }})
    await userModule.actions.getUserInfo({ commit })
    expect(state.name).toBe('admin')
    expect(state.defaultPassword).toBe(true)
    expect(state.identityLoaded).toBe(true)
  })

  it('does not clear the flag after a rejected password update', async() => {
    api.changePassword.mockRejectedValue(new Error('rejected'))
    await expect(userModule.actions.changePassword({ commit }, {})).rejects.toThrow('rejected')
    expect(state.defaultPassword).toBe(true)
    expect(auth.removeDefaultPassword).not.toHaveBeenCalled()
  })

  it('clears restriction only after successful password update', async() => {
    api.changePassword.mockResolvedValue({ data: null })
    await userModule.actions.changePassword({ commit }, {})
    expect(state.defaultPassword).toBe(false)
    expect(auth.removeDefaultPassword).toHaveBeenCalledTimes(1)
  })

  it('clears local credentials even if remote logout fails', async() => {
    api.logout.mockRejectedValue(new Error('offline'))
    const dispatch = jest.fn(() => Promise.resolve())
    await userModule.actions.logout({ dispatch })
    expect(dispatch).toHaveBeenCalledWith('resetToken')
    await userModule.actions.resetToken({ commit })
    expect(auth.removeToken).toHaveBeenCalled()
    expect(auth.removeName).toHaveBeenCalled()
    expect(auth.removeServerId).toHaveBeenCalled()
    expect(auth.removeDefaultPassword).toHaveBeenCalled()
    expect(resetRouter).toHaveBeenCalled()
    expect(state.identityLoaded).toBe(false)
  })
})
