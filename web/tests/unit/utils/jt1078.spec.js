import { isJT1078Enabled, setJT1078RouteVisibility } from '@/utils/jt1078'

describe('JT1078 route visibility', () => {
  it('only enables JT1078 when the backend explicitly enables it', () => {
    expect(isJT1078Enabled({ jt1078Config: { enable: true } })).toBe(true)
    expect(isJT1078Enabled({ jt1078Config: { enable: false } })).toBe(false)
    expect(isJT1078Enabled({ jt1078Config: { port: 21078 } })).toBe(false)
    expect(isJT1078Enabled({})).toBe(false)
  })

  it('hides all JT1078 routes when the service is disabled', () => {
    const routes = [
      { path: '/device', children: [
        { path: '/jtDevice', meta: { requiresJt1078: true }, hidden: true },
        { path: '/device', hidden: false }
      ] }
    ]

    setJT1078RouteVisibility(routes, false)
    expect(routes[0].children[0].hidden).toBe(true)

    setJT1078RouteVisibility(routes, true)
    expect(routes[0].children[0].hidden).toBe(false)
  })
})
