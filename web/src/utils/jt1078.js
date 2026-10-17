export function isJT1078Enabled(systemConfig) {
  return !!(systemConfig && systemConfig.jt1078Config && systemConfig.jt1078Config.enable === true)
}

export function setJT1078RouteVisibility(routes, enabled) {
  if (!Array.isArray(routes)) {
    return
  }
  routes.forEach(route => {
    if (route.meta && route.meta.requiresJt1078) {
      route.hidden = !enabled
    }
    setJT1078RouteVisibility(route.children, enabled)
  })
}
