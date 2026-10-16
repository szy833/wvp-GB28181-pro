import service from '@/utils/request'
import store from '@/store'
import router from '@/router'

jest.mock('axios', () => ({
  create: jest.fn(() => ({ interceptors: { request: { use: jest.fn() }, response: { use: jest.fn() }}}))
}))
jest.mock('@/store', () => ({ getters: { showConfirmBoxForLoginLose: true }, commit: jest.fn() }))
jest.mock('@/router', () => ({ currentRoute: { path: '/' }, replace: jest.fn(() => Promise.resolve()) }))
jest.mock('element-ui', () => ({ MessageBox: { confirm: jest.fn() }, Message: { error: jest.fn() }}))
jest.mock('@/utils/auth', () => ({ getToken: jest.fn() }))

const rejectResponse = service.interceptors.response.use.mock.calls[0][1]

describe('password HTTP failures', () => {
  beforeEach(() => jest.clearAllMocks())

  it('rejects a normal HTTP failure instead of converting it to success', async() => {
    const error = { response: { status: 500, data: { msg: 'failure' }}}
    await expect(rejectResponse(error)).rejects.toBe(error)
  })

  it('rejects network failure without dereferencing missing response', async() => {
    const error = new Error('offline')
    await expect(rejectResponse(error)).rejects.toBe(error)
  })

  it('resynchronizes default password flag and redirects on server 403', async() => {
    const error = { response: { status: 403, data: { defaultPassword: true }}}
    await expect(rejectResponse(error)).rejects.toBe(error)
    expect(store.commit).toHaveBeenCalledWith('user/SET_DEFAULT_PASSWORD', true)
    expect(router.replace).toHaveBeenCalledWith('/forceChangePassword', expect.any(Function), expect.any(Function))
  })

  it('supports Vue Router 3.0 which does not return a navigation Promise', async() => {
    router.replace.mockReturnValueOnce(undefined)
    const error = { response: { status: 403, data: { defaultPassword: true }}}
    await expect(rejectResponse(error)).rejects.toBe(error)
  })
})
