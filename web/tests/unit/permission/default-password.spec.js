import router from '@/router'
import store from '@/store'
import { getToken } from '@/utils/auth'
import '@/permission'

jest.mock('@/router', () => ({ beforeEach: jest.fn(), afterEach: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: {}}, dispatch: jest.fn(), commit: jest.fn() }))
jest.mock('@/utils/auth', () => ({ getToken: jest.fn(), getServerId: jest.fn() }))
jest.mock('nprogress', () => ({ configure: jest.fn(), start: jest.fn(), done: jest.fn() }))
jest.mock('nprogress/nprogress.css', () => ({}))
jest.mock('@/utils/get-page-title', () => jest.fn(() => 'WVP'))

const guard = router.beforeEach.mock.calls[0][0]
const route = path => ({ path, meta: {} })

describe('forced password routing', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    getToken.mockReturnValue('token')
    store.state.user = { identityLoaded: false, defaultPassword: false }
    store.dispatch.mockImplementation(async() => {
      store.state.user.identityLoaded = true
      store.state.user.defaultPassword = true
    })
  })

  it('refreshes server identity and redirects even if no flag cookie exists', async() => {
    const next = jest.fn()
    await guard(route('/'), route('/login'), next)
    expect(store.dispatch).toHaveBeenCalledWith('user/getUserInfo')
    expect(next).toHaveBeenCalledWith({ path: '/forceChangePassword', replace: true })
  })

  it('allows the forced page without a redirect loop', async() => {
    store.state.user = { identityLoaded: true, defaultPassword: true }
    const next = jest.fn()
    await guard(route('/forceChangePassword'), route('/'), next)
    expect(next).toHaveBeenCalledTimes(1)
    expect(next).toHaveBeenCalledWith(undefined)
    expect(store.dispatch).not.toHaveBeenCalled()
  })

  it('does not expose the forced page without authentication', async() => {
    getToken.mockReturnValue(undefined)
    const next = jest.fn()
    await guard(route('/forceChangePassword'), route('/'), next)
    expect(next).toHaveBeenCalledWith('/login?redirect=/forceChangePassword')
  })

  it('sends an already changed user away from the forced page', async() => {
    store.state.user = { identityLoaded: true, defaultPassword: false }
    const next = jest.fn()
    await guard(route('/forceChangePassword'), route('/'), next)
    expect(next).toHaveBeenCalledWith({ path: '/', replace: true })
  })

  it('clears stale credentials if identity refresh fails', async() => {
    store.dispatch.mockRejectedValueOnce(new Error('expired')).mockResolvedValueOnce()
    const next = jest.fn()
    await guard(route('/'), route('/'), next)
    expect(store.dispatch).toHaveBeenCalledWith('user/resetToken')
    expect(next).toHaveBeenCalledWith('/login')
  })
})
