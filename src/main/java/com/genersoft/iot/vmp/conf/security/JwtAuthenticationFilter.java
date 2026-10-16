package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.ArrayList;

/**
 * jwt token 过滤器
 */

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final static String WSHeader = "sec-websocket-protocol";


    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IUserService userService;


    @Override
    protected void doFilterInternal(HttpServletRequest servletRequest, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(servletRequest);
        // 忽略登录请求的token验证
        String requestURI = request.getRequestURI().substring(request.getContextPath().length());
        if ((requestURI.startsWith("/doc.html") || requestURI.startsWith("/swagger-ui")  ) && !userSetting.getDocEnable()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (requestURI.equalsIgnoreCase("/api/user/login")) {
            chain.doFilter(request, response);
            return;
        }

        if (!userSetting.getInterfaceAuthentication()) {
            UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(null, null, new ArrayList<>() );
            SecurityContextHolder.getContext().setAuthentication(token);
            chain.doFilter(request, response);
            return;
        }

        String jwt = request.getHeader(JwtUtils.getHeader());
        // 这里如果没有jwt，继续往后走，因为后面还有鉴权管理器等去判断是否拥有身份凭证，所以是可以放行的
        // 没有jwt相当于匿名访问，若有一些接口是需要权限的，则不能访问这些接口

        // websocket 鉴权信息默认存储在这里
        String secWebsocketProtocolHeader = request.getHeader(WSHeader);
        if (StringUtils.isBlank(jwt)) {

            if (secWebsocketProtocolHeader != null) {
                jwt = secWebsocketProtocolHeader;
                response.setHeader(WSHeader, secWebsocketProtocolHeader);
            }else {
                jwt = request.getParameter(JwtUtils.getHeader());
            }
            if (StringUtils.isBlank(jwt)) {
                jwt = request.getHeader(JwtUtils.getApiKeyHeader());
                if (StringUtils.isBlank(jwt)) {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && authentication.isAuthenticated()
                            && authentication.getPrincipal() instanceof User sessionUser
                            && !allowCurrentUser(sessionUser, request, response, requestURI)) {
                        return;
                    }
                    chain.doFilter(request, response);
                    return;
                }
            }
        }

        JwtUser jwtUser = JwtUtils.verifyToken(jwt);
        // TODO 处理各个状态
        switch (jwtUser.getStatus()){
            case EXPIRED:
                response.setStatus(401);
                SecurityContextHolder.clearContext();
                // 异常
                return;
            case EXCEPTION:
                // 过期
                response.setStatus(400);
                SecurityContextHolder.clearContext();
                return;
            case EXPIRING_SOON:
                // 即将过期
//                return;
            default:
        }
        // 构建UsernamePasswordAuthenticationToken,这里密码为null，是因为提供了正确的JWT,实现自动登录
        User user = new User();
        user.setId(jwtUser.getUserId());
        user.setUsername(jwtUser.getUserName());
        user.setPassword(jwtUser.getPassword());
        Role role = new Role();
        role.setId(jwtUser.getRoleId());
        user.setRole(role);
        if (!allowCurrentUser(user, request, response, requestURI)) {
            return;
        }
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(user, jwtUser.getPassword(), new ArrayList<>() );
        SecurityContextHolder.getContext().setAuthentication(token);
        chain.doFilter(request, response);
    }

    private boolean allowCurrentUser(User user, HttpServletRequest request, HttpServletResponse response,
                                     String requestURI) throws IOException {
        User dbUser = userService.getUserById(user.getId());
        if (dbUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            SecurityContextHolder.clearContext();
            return false;
        }
        user.setDefaultPassword(dbUser.isDefaultPassword());

        // Read-only identity refresh lets the SPA recover even if its hint cookie was removed.
        if (user.isDefaultPassword()) {
            boolean passwordChange = "POST".equals(request.getMethod()) && "/api/user/changePassword".equals(requestURI);
            boolean identityRead = "POST".equals(request.getMethod()) && "/api/user/userInfo".equals(requestURI);
            boolean logout = ("GET".equals(request.getMethod()) || "POST".equals(request.getMethod()))
                    && "/api/user/logout".equals(requestURI);
            if (!passwordChange && !identityRead && !logout) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"msg\":\"请先修改默认密码\",\"defaultPassword\":true}");
                return false;
            }
        }

        return true;
    }
}
