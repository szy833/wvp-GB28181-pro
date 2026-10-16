package com.genersoft.iot.vmp.conf.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PasswordComplexityValidator {
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[\\x21-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7B-\\x7E])[\\x21-\\x7E]{10,}$");

    public boolean validatePassword(String password, String username) {
        return password != null && username != null && !username.isEmpty()
                && PASSWORD_PATTERN.matcher(password).matches()
                && !password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT));
    }

    public String getValidationErrorMessage() {
        return "密码至少10位，包含大写字母、小写字母、数字和特殊符号，仅允许可见ASCII字符（不含空格），且不能包含用户名";
    }
}
