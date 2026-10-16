package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DefaultPasswordSecurityChainTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private MockedStatic<JwtUtils> jwt;
    private User user;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        user = new User();
        user.setId(1);
        user.setDefaultPassword(true);
        when(context.getBean(IUserService.class).getUserById(1)).thenReturn(user);
        JwtUser principal = new JwtUser();
        principal.setUserId(1);
        principal.setUserName("admin");
        principal.setRoleId(1);
        principal.setStatus(JwtUser.TokenStatus.NORMAL);
        jwt = mockStatic(JwtUtils.class);
        jwt.when(JwtUtils::getHeader).thenReturn("access-token");
        jwt.when(JwtUtils::getApiKeyHeader).thenReturn("api-key");
        jwt.when(() -> JwtUtils.verifyToken("token")).thenReturn(principal);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach void tearDown() {
        if (jwt != null) jwt.close();
        if (context != null) context.close();
    }

    @Test void fullChainBlocksBusinessButAllowsIdentityPasswordAndLogout() throws Exception {
        mvc.perform(get("/api/test").header("access-token", "token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/user/userInfo").header("access-token", "token"))
                .andExpect(status().isOk()).andExpect(content().string("identity"));
        mvc.perform(post("/api/user/changePassword").header("access-token", "token"))
                .andExpect(status().isOk()).andExpect(content().string("password"));
        mvc.perform(get("/api/user/logout").header("access-token", "token")).andExpect(status().isOk());
    }

    @Test void apiKeyIsRestrictedAndChangedUserCanAccessBusiness() throws Exception {
        mvc.perform(get("/api/test").header("api-key", "token")).andExpect(status().isForbidden());
        user.setDefaultPassword(false);
        mvc.perform(get("/api/test").header("access-token", "token"))
                .andExpect(status().isOk()).andExpect(content().string("business"));
    }

    @Test void configuredContextPathStillAllowsRecoveryEndpoint() throws Exception {
        mvc.perform(post("/wvp/api/user/userInfo").contextPath("/wvp").header("access-token", "token"))
                .andExpect(status().isOk()).andExpect(content().string("identity"));
    }

    @Test void savedHttpSessionWithoutTokenIsAlsoRestricted() throws Exception {
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        mvc.perform(get("/api/test").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/user/userInfo").session(session)).andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @Import(WebSecurityConfig.class)
    static class TestConfig {
        @Bean UserSetting userSetting() {
            UserSetting value = new UserSetting();
            value.setInterfaceAuthentication(true);
            return value;
        }
        @Bean IUserService userService() { return mock(IUserService.class); }
        @Bean DefaultUserDetailsServiceImpl userDetailsService() { return mock(DefaultUserDetailsServiceImpl.class); }
        @Bean LogoutHandler logoutHandler() { return new LogoutHandler(); }
        @Bean AnonymousAuthenticationEntryPoint anonymousEntryPoint() { return new AnonymousAuthenticationEntryPoint(); }
        @Bean JwtAuthenticationFilter jwtFilter() { return new JwtAuthenticationFilter(); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/api/test") String business() { return "business"; }
        @PostMapping("/api/user/userInfo") String identity() { return "identity"; }
        @PostMapping("/api/user/changePassword") String password() { return "password"; }
    }
}
