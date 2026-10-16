import router from './router'
import store from './store'
import NProgress from 'nprogress' // progress bar
import 'nprogress/nprogress.css' // progress bar style
import { getToken, getServerId } from '@/utils/auth' // get token from cookie
import getPageTitle from '@/utils/get-page-title'

NProgress.configure({ showSpinner: false }) // NProgress Configuration

const whiteList = ['/login', '/play/share'] // no redirect whitelist

router.beforeEach(async(to, from, next) => {
  // start progress bar
  NProgress.start()

  // set page title
  document.title = getPageTitle(to.meta.title)

  // determine whether the user has logged in
  const hasToken = getToken()

  if (hasToken) {
    if (!store.state.user.identityLoaded) {
      try {
        // Cookies are only hints; refresh the restriction from the server on page reload.
        await store.dispatch('user/getUserInfo')
        store.commit('user/SET_SERVER_ID', getServerId())
      } catch (error) {
        await store.dispatch('user/resetToken')
        next('/login')
        NProgress.done()
        return
      }
    }
    if (store.state.user.defaultPassword) {
      next(to.path === '/forceChangePassword' ? undefined : { path: '/forceChangePassword', replace: true })
      NProgress.done()
      return
    }
    if (to.path === '/login') {
      // if is logged in, redirect to the home page
      next({ path: '/' })
      NProgress.done()
    } else {
      next(to.path === '/forceChangePassword' ? { path: '/', replace: true } : undefined)
    }
  } else {
    /* has no token*/

    if (whiteList.indexOf(to.path) !== -1) {
      // in the free login whitelist, go directly
      next()
    } else {
      // other pages that do not have permission to access are redirected to the login page.
      next(`/login?redirect=${to.path}`)
      NProgress.done()
    }
  }
})

router.afterEach(() => {
  // finish progress bar
  NProgress.done()
})
