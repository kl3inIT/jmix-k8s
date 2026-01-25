package com.company.jmixk8s.listener;

import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

@Component
public class AuthenticationEventListener {

  @EventListener
  public void onAuthenticationSuccess(final AuthenticationSuccessEvent event) {}

  @EventListener
  public void onInteractiveAuthenticationSuccess(
      final InteractiveAuthenticationSuccessEvent event) {}

  @EventListener
  public void onAuthenticationFailure(final AbstractAuthenticationFailureEvent event) {}

  @EventListener
  public void onLogoutSuccess(final LogoutSuccessEvent event) {}
}
