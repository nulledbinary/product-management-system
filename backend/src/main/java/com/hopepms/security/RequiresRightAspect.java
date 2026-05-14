package com.hopepms.security;

import com.hopepms.util.ApiException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RequiresRightAspect {

    @Around("@annotation(annotation)")
    public Object enforce(ProceedingJoinPoint pjp, RequiresRight annotation) throws Throwable {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof HopePrincipal p)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in required");
        }
        if (!p.hasRight(annotation.value())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Missing right: " + annotation.value());
        }
        return pjp.proceed();
    }
}
