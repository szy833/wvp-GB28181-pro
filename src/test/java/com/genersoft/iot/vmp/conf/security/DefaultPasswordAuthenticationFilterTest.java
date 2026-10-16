package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

class DefaultPasswordAuthenticationFilterTest {
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
    private final IUserService users = mock(IUserService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final UserSetting settings = new UserSetting();
    private final User user = new User();
    private final JwtUser jwtUser = new JwtUser();
    private MockedStatic<JwtUtils> jwt;

    @BeforeEach void setUp() {
        settings.setInterfaceAuthentication(true);
        user.setId(1);
        user.setUsername("admin");
        user.setDefaultPassword(true);
        jwtUser.setUserId(1);
        jwtUser.setUserName("admin");
        jwtUser.setRoleId(1);
        jwtUser.setStatus(JwtUser.TokenStatus.NORMAL);
        when(users.getUserById(1)).thenReturn(user);
        ReflectionTestUtils.setField(filter, "userSetting", settings);
        ReflectionTestUtils.setField(filter, "userService", users);
        jwt = mockStatic(JwtUtils.class);
        jwt.when(JwtUtils::getHeader).thenReturn("access-token");
        jwt.when(JwtUtils::getApiKeyHeader).thenReturn("api-key");
        jwt.when(() -> JwtUtils.verifyToken("token")).thenReturn(jwtUser);
    }

    @AfterEach void tearDown() { jwt.close(); SecurityContextHolder.clearContext(); }

    @Test void flaggedUserGetsMachineReadable403AndCannotEnterBusinessHandler() throws Exception {
        var response = invoke("GET", "/api/user/users", "access-token", "");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"defaultPassword\":true"));
        verifyNoInteractions(chain);
    }

    @Test void sameRestrictionAppliesToApiKeyAndWebsocketCredentials() throws Exception {
        assertEquals(403, invoke("GET", "/api/device/query/devices", "api-key", "").getStatus());
        assertEquals(403, invoke("GET", "/websocket/log", "sec-websocket-protocol", "").getStatus());
        verifyNoInteractions(chain);
    }

    @Test void passwordIdentityAndLogoutAreAllowedWithContextPath() throws Exception {
        for (String path : new String[]{"/api/user/changePassword", "/api/user/userInfo", "/api/user/logout"}) {
            assertEquals(200, invoke("POST", path, "access-token", "/wvp").getStatus());
        }
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test void wrongMethodAndLookalikePathAreNotAllowlisted() throws Exception {
        assertEquals(403, invoke("GET", "/api/user/changePassword", "access-token", "").getStatus());
        assertEquals(403, invoke("POST", "/api/user/changePasswordForAdmin", "access-token", "").getStatus());
        assertEquals(403, invoke("POST", "/api/user/changePassword/extra", "access-token", "").getStatus());
        verifyNoInteractions(chain);
    }

    @Test void databaseFlagIsRecheckedOnEveryRequest() throws Exception {
        assertEquals(403, invoke("GET", "/api/user/users", "access-token", "").getStatus());
        user.setDefaultPassword(false);
        assertEquals(200, invoke("GET", "/api/user/users", "access-token", "").getStatus());
        verify(chain).doFilter(any(), any());
    }

    @Test void deletedUserIsNotSilentlyTreatedAsUnrestricted() throws Exception {
        when(users.getUserById(1)).thenReturn(null);
        assertEquals(401, invoke("GET", "/api/user/users", "access-token", "").getStatus());
        verifyNoInteractions(chain);
    }

    @Test void disabledAuthenticationKeepsExistingConfigurationMeaning() throws Exception {
        settings.setInterfaceAuthentication(false);
        assertEquals(200, invoke("GET", "/api/user/users", "access-token", "").getStatus());
        verifyNoInteractions(users);
        verify(chain).doFilter(any(), any());
    }

    @Test void authenticatedSessionWithoutJwtCannotBypassRestriction() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(new MockHttpServletRequest("GET", "/api/user/users"), response, chain);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void expiredTokenDoesNotContinueToAuthenticatedSessionOrBusinessHandler() throws Exception {
        jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
        assertEquals(401, invoke("GET", "/api/user/users", "access-token", "").getStatus());
        verifyNoInteractions(chain);
    }

    private MockHttpServletResponse invoke(String method, String path, String header, String context) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context);
        request.addHeader(header, "token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }
}
