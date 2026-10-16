package com.genersoft.iot.vmp.vmanager.user;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.security.PasswordComplexityValidator;
import com.genersoft.iot.vmp.conf.security.dto.LoginUser;
import com.genersoft.iot.vmp.service.IRoleService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import org.springframework.util.DigestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPasswordControllerTest {
    private final UserController controller = new UserController();
    private final IUserService users = mock(IUserService.class);
    private final AuthenticationManager authentication = mock(AuthenticationManager.class);
    private final IRoleService roles = mock(IRoleService.class);

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "authenticationManager", authentication);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        User admin = user(1, "admin", 1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        ReflectionTestUtils.setField(controller, "passwordComplexityValidator", new PasswordComplexityValidator());
    }

    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }

    @Test void adminCannotResetToWeakPassword() {
        when(users.getUserById(2)).thenReturn(user(2, "operator", 2));
        when(users.changePassword(anyInt(), anyString())).thenReturn(true);
        assertThrows(ControllerException.class, () -> controller.changePasswordForAdmin(2, "admin"));
        verify(users, never()).changePassword(anyInt(), anyString());
    }

    @Test void adminCannotSetPasswordContainingTargetUsername() {
        when(users.getUserById(2)).thenReturn(user(2, "operator", 2));
        when(users.changePassword(anyInt(), anyString())).thenReturn(true);
        assertThrows(ControllerException.class, () -> controller.changePasswordForAdmin(2, "Operator12!"));
        verify(users, never()).changePassword(anyInt(), anyString());
    }

    @Test void nonAdminPasswordResetIsRejectedInsteadOfReportingSuccess() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user(2, "operator", 2), null, List.of()));
        assertThrows(ControllerException.class, () -> controller.changePasswordForAdmin(1, "Abcdefg12!"));
        verifyNoInteractions(users);
    }

    @Test void adminCanUseQuoteAsOnlySpecialCharacter() {
        when(users.getUserById(2)).thenReturn(user(2, "operator", 2));
        when(users.changePassword(2, hash("Abcdefg12\""))).thenReturn(true);
        controller.changePasswordForAdmin(2, "Abcdefg12\"");
        verify(users).changePassword(2, hash("Abcdefg12\""));
    }

    @Test void missingResetTargetIsRejected() {
        assertThrows(ControllerException.class, () -> controller.changePasswordForAdmin(999, "Abcdefg12!"));
        verify(users, never()).changePassword(anyInt(), anyString());
    }

    @Test void ownPasswordMustPassPolicyBeforeWriting() {
        authenticateAdmin();
        assertThrows(ControllerException.class, () -> controller.changePassword("old-md5", "admin"));
        verify(users, never()).changePassword(anyInt(), anyString());
    }

    @Test void ownPasswordIsHashedAndWrittenThroughDedicatedService() {
        authenticateAdmin();
        when(users.changePassword(1, hash("Abcdefg12!"))).thenReturn(true);
        controller.changePassword("old-md5", "Abcdefg12!");
        verify(users).changePassword(1, hash("Abcdefg12!"));
    }

    @Test void addRejectsWeakPasswordAndUsernameInPassword() {
        assertThrows(ControllerException.class, () -> controller.add("operator", "weak", 2));
        assertThrows(ControllerException.class, () -> controller.add("operator", "Operator12!", 2));
        verify(users, never()).addUser(any());
    }

    @Test void addAcceptsStrongPasswordAndStoresHash() {
        Role role = new Role();
        role.setId(2);
        when(roles.getRoleById(2)).thenReturn(role);
        when(users.addUser(any())).thenReturn(1);
        controller.add("operator", "Abcdefg12!", 2);
        verify(users).addUser(argThat(u -> hash("Abcdefg12!").equals(u.getPassword()) && !u.isDefaultPassword()));
    }

    @Test void identityRefreshReadsCurrentDatabaseFlag() {
        User admin = user(1, "admin", 1);
        admin.setDefaultPassword(true);
        when(users.getUserById(1)).thenReturn(admin);
        assertTrue(controller.getUserInfo().isDefaultPassword());
        admin.setDefaultPassword(false);
        assertFalse(controller.getUserInfo().isDefaultPassword());
    }

    private void authenticateAdmin() {
        LoginUser login = new LoginUser(user(1, "admin", 1), LocalDateTime.now());
        when(authentication.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    private static String hash(String value) {
        return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static User user(int id, String name, int roleId) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        Role role = new Role();
        role.setId(roleId);
        user.setRole(role);
        return user;
    }
}
