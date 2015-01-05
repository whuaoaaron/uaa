/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/

package org.cloudfoundry.identity.uaa.authorization;

import java.security.Principal;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.cloudfoundry.identity.uaa.oauth.token.OpenIdToken;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.BadClientCredentialsException;
import org.springframework.security.oauth2.common.exceptions.ClientAuthenticationException;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.common.exceptions.InvalidRequestException;
import org.springframework.security.oauth2.common.exceptions.OAuth2Exception;
import org.springframework.security.oauth2.common.exceptions.RedirectMismatchException;
import org.springframework.security.oauth2.common.exceptions.UnapprovedClientAuthenticationException;
import org.springframework.security.oauth2.common.exceptions.UnsupportedResponseTypeException;
import org.springframework.security.oauth2.common.exceptions.UserDeniedAuthorizationException;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientRegistrationException;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.OAuth2RequestValidator;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.approval.DefaultUserApprovalHandler;
import org.springframework.security.oauth2.provider.approval.UserApprovalHandler;
import org.springframework.security.oauth2.provider.code.AuthorizationCodeServices;
import org.springframework.security.oauth2.provider.code.InMemoryAuthorizationCodeServices;
import org.springframework.security.oauth2.provider.endpoint.AbstractEndpoint;
import org.springframework.security.oauth2.provider.endpoint.DefaultRedirectResolver;
import org.springframework.security.oauth2.provider.endpoint.RedirectResolver;
import org.springframework.security.oauth2.provider.implicit.ImplicitTokenRequest;
import org.springframework.security.oauth2.provider.request.DefaultOAuth2RequestValidator;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.DefaultSessionAttributeStore;
import org.springframework.web.bind.support.SessionAttributeStore;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriTemplate;

/**
 * Authorization endpoint that returns id_token's if requested.
 * This is a copy of AuthorizationEndpoint.java in
 * Spring Security Oauth2. As that code does not allow
 * for redirect responses to be customized, as desired by
 * https://github.com/fhanik/spring-security-oauth/compare/feature/extendable-redirect-generator?expand=1
 */
@Controller
@SessionAttributes("authorizationRequest")
public class UaaAuthorizationEndpoint extends AbstractEndpoint {

    private AuthorizationCodeServices authorizationCodeServices = new InMemoryAuthorizationCodeServices();

    private RedirectResolver redirectResolver = new DefaultRedirectResolver();

    private UserApprovalHandler userApprovalHandler = new DefaultUserApprovalHandler();

    private SessionAttributeStore sessionAttributeStore = new DefaultSessionAttributeStore();

    private OAuth2RequestValidator oauth2RequestValidator = new DefaultOAuth2RequestValidator();

    private String userApprovalPage = "forward:/oauth/confirm_access";

    private String errorPage = "forward:/oauth/error";

    private Object implicitLock = new Object();

    private Boolean fallbackToAuthcode = false;

    public void setFallbackToAuthcode(Boolean fallbackToAuthcode) {
        this.fallbackToAuthcode = fallbackToAuthcode;
    }

    public void setSessionAttributeStore(SessionAttributeStore sessionAttributeStore) {
        this.sessionAttributeStore = sessionAttributeStore;
    }

    public void setErrorPage(String errorPage) {
        this.errorPage = errorPage;
    }

    @RequestMapping(value = "/oauth/authorize")
    public ModelAndView authorize(Map<String, Object> model, @RequestParam Map<String, String> parameters,
                                  SessionStatus sessionStatus, Principal principal) {

        // Pull out the authorization request first, using the OAuth2RequestFactory. All further logic should
        // query off of the authorization request instead of referring back to the parameters map. The contents of the
        // parameters map will be stored without change in the AuthorizationRequest object once it is created.
        AuthorizationRequest authorizationRequest;
        try {
            authorizationRequest = getOAuth2RequestFactory().createAuthorizationRequest(parameters);
        } catch (NoSuchClientException x) {
            throw new InvalidClientException(x.getMessage());
        }

        Set<String> responseTypes = authorizationRequest.getResponseTypes();

        if (!responseTypes.contains("token") && !responseTypes.contains("code")) {
            throw new UnsupportedResponseTypeException("Unsupported response types: " + responseTypes);
        }

        if (authorizationRequest.getClientId() == null) {
            throw new InvalidClientException("A client id must be provided");
        }

        try {

            if (!(principal instanceof Authentication) || !((Authentication) principal).isAuthenticated()) {
                throw new InsufficientAuthenticationException(
                    "User must be authenticated with Spring Security before authorization can be completed.");
            }

            ClientDetails client = getClientDetailsService().loadClientByClientId(authorizationRequest.getClientId());

            // The resolved redirect URI is either the redirect_uri from the parameters or the one from
            // clientDetails. Either way we need to store it on the AuthorizationRequest.
            String redirectUriParameter = authorizationRequest.getRequestParameters().get(OAuth2Utils.REDIRECT_URI);
            String resolvedRedirect = redirectResolver.resolveRedirect(redirectUriParameter, client);
            if (!StringUtils.hasText(resolvedRedirect)) {
                throw new RedirectMismatchException(
                    "A redirectUri must be either supplied or preconfigured in the ClientDetails");
            }
            authorizationRequest.setRedirectUri(resolvedRedirect);

            // We intentionally only validate the parameters requested by the client (ignoring any data that may have
            // been added to the request by the manager).
            oauth2RequestValidator.validateScope(authorizationRequest, client);

            // Some systems may allow for approval decisions to be remembered or approved by default. Check for
            // such logic here, and set the approved flag on the authorization request accordingly.
            authorizationRequest = userApprovalHandler.checkForPreApproval(authorizationRequest,
                (Authentication) principal);
            // TODO: is this call necessary?
            boolean approved = userApprovalHandler.isApproved(authorizationRequest, (Authentication) principal);
            authorizationRequest.setApproved(approved);

            // Validation is all done, so we can check for auto approval...
            if (authorizationRequest.isApproved()) {
                if (responseTypes.contains("token") || responseTypes.contains("id_token")) {
                    ModelAndView modelAndView = getImplicitGrantResponse(authorizationRequest, (Authentication) principal);
                    if (modelAndView != null) {
                        return modelAndView;
                    }
                }
                if (responseTypes.contains("code")) {
                    return new ModelAndView(getAuthorizationCodeResponse(authorizationRequest,
                        (Authentication) principal));
                }
            }

            // Place auth request into the model so that it is stored in the session
            // for approveOrDeny to use. That way we make sure that auth request comes from the session,
            // so any auth request parameters passed to approveOrDeny will be ignored and retrieved from the session.
            model.put("authorizationRequest", authorizationRequest);

            return getUserApprovalPageResponse(model, authorizationRequest, (Authentication) principal);

        } catch (RuntimeException e) {
            sessionStatus.setComplete();
            throw e;
        }

    }

    @RequestMapping(value = "/oauth/authorize", method = RequestMethod.POST, params = OAuth2Utils.USER_OAUTH_APPROVAL)
    public View approveOrDeny(@RequestParam Map<String, String> approvalParameters, Map<String, ?> model,
                              SessionStatus sessionStatus, Principal principal) {

        if (!(principal instanceof Authentication)) {
            sessionStatus.setComplete();
            throw new InsufficientAuthenticationException(
                "User must be authenticated with Spring Security before authorizing an access token.");
        }

        AuthorizationRequest authorizationRequest = (AuthorizationRequest) model.get("authorizationRequest");

        if (authorizationRequest == null) {
            sessionStatus.setComplete();
            throw new InvalidRequestException("Cannot approve uninitialized authorization request.");
        }

        try {
            Set<String> responseTypes = authorizationRequest.getResponseTypes();

            authorizationRequest.setApprovalParameters(approvalParameters);
            authorizationRequest = userApprovalHandler.updateAfterApproval(authorizationRequest,
                (Authentication) principal);
            boolean approved = userApprovalHandler.isApproved(authorizationRequest, (Authentication) principal);
            authorizationRequest.setApproved(approved);

            if (authorizationRequest.getRedirectUri() == null) {
                sessionStatus.setComplete();
                throw new InvalidRequestException("Cannot approve request when no redirect URI is provided.");
            }

            if (!authorizationRequest.isApproved()) {
                return new RedirectView(getUnsuccessfulRedirect(authorizationRequest,
                    new UserDeniedAuthorizationException("User denied access"), responseTypes.contains("token")),
                    false, true, false);
            }

            if (responseTypes.contains("token") || responseTypes.contains("id_token")) {
                ModelAndView modelAndView = getImplicitGrantResponse(authorizationRequest, (Authentication) principal);
                if (modelAndView != null) {
                    return modelAndView.getView();
                }
            }

            return getAuthorizationCodeResponse(authorizationRequest, (Authentication) principal);
        } finally {
            sessionStatus.setComplete();
        }

    }

    // We need explicit approval from the user.
    private ModelAndView getUserApprovalPageResponse(Map<String, Object> model,
                                                     AuthorizationRequest authorizationRequest, Authentication principal) {
        logger.debug("Loading user approval page: " + userApprovalPage);
        model.putAll(userApprovalHandler.getUserApprovalRequest(authorizationRequest, principal));
        return new ModelAndView(userApprovalPage, model);
    }

    // We can grant a token and return it with implicit approval.
    private ModelAndView getImplicitGrantResponse(AuthorizationRequest authorizationRequest, Authentication authentication) {
        OAuth2AccessToken accessToken;
        try {
            TokenRequest tokenRequest = getOAuth2RequestFactory().createTokenRequest(authorizationRequest, "implicit");
            OAuth2Request storedOAuth2Request = getOAuth2RequestFactory().createOAuth2Request(authorizationRequest);
            accessToken = getAccessTokenForImplicitGrant(tokenRequest, storedOAuth2Request);
            if (accessToken == null) {
                throw new UnsupportedResponseTypeException("Unsupported response type: token");
            }
            return new ModelAndView(new RedirectView(appendAccessToken(authorizationRequest, accessToken, authentication), false, true,
                false));
        } catch (OAuth2Exception e) {
            if (authorizationRequest.getResponseTypes().contains("token") || fallbackToAuthcode == false) {
                return new ModelAndView(new RedirectView(getUnsuccessfulRedirect(authorizationRequest, e, true), false,
                        true, false));
           }
        }
       return null;
    }

    private OAuth2AccessToken getAccessTokenForImplicitGrant(TokenRequest tokenRequest,
                                                             OAuth2Request storedOAuth2Request) {
        OAuth2AccessToken accessToken = null;
        // These 1 method calls have to be atomic, otherwise the ImplicitGrantService can have a race condition where
        // one thread removes the token request before another has a chance to redeem it.
        synchronized (this.implicitLock) {
            accessToken = getTokenGranter().grant("implicit", new ImplicitTokenRequest(tokenRequest, storedOAuth2Request));
        }
        return accessToken;
    }

    private View getAuthorizationCodeResponse(AuthorizationRequest authorizationRequest, Authentication authUser) {
        try {
            return new RedirectView(getSuccessfulRedirect(authorizationRequest,
                generateCode(authorizationRequest, authUser)), false, true, false);
        } catch (OAuth2Exception e) {
            return new RedirectView(getUnsuccessfulRedirect(authorizationRequest, e, false), false, true, false);
        }
    }

    private String appendAccessToken(AuthorizationRequest authorizationRequest,
                                     OAuth2AccessToken accessToken,
                                     Authentication authUser) {

        Map<String, Object> vars = new HashMap<>();

        String requestedRedirect = authorizationRequest.getRedirectUri();
        if (accessToken == null) {
            throw new InvalidRequestException("An implicit grant could not be made");
        }
        StringBuilder url = new StringBuilder(requestedRedirect);
        if (requestedRedirect.contains("#")) {
            url.append("&");
        } else {
            url.append("#");
        }

        url.append("token_type={token_type}");
        vars.put("token_type", accessToken.getTokenType());

        //only append access token if grant_type is implicit
        //or token is part of the response type
        if (authorizationRequest.getResponseTypes().contains("token")) {
            url.append("&access_token={access_token}");
            vars.put("access_token", accessToken.getValue());
        }

        if (accessToken instanceof OpenIdToken &&
            authorizationRequest.getResponseTypes().contains(OpenIdToken.ID_TOKEN)) {
            url.append("&").append(OpenIdToken.ID_TOKEN).append("={id_token}");
            vars.put(OpenIdToken.ID_TOKEN, ((OpenIdToken) accessToken).getIdTokenValue());
        }

        if (authorizationRequest.getResponseTypes().contains("code")) {
            String code = generateCode(authorizationRequest, authUser);
            url.append("&code={code}");
            vars.put("code", code);
        }

        String state = authorizationRequest.getState();
        if (state != null) {
            url.append("&state={state}");
            vars.put("state", state);
        }
        Date expiration = accessToken.getExpiration();
        if (expiration != null) {
            long expires_in = (expiration.getTime() - System.currentTimeMillis()) / 1000;
            url.append("&expires_in={expires_in}");
            vars.put("expires_in", expires_in);
        }
        String originalScope = authorizationRequest.getRequestParameters().get(OAuth2Utils.SCOPE);
        if (originalScope == null || !OAuth2Utils.parseParameterList(originalScope).equals(accessToken.getScope())) {
            url.append("&" + OAuth2Utils.SCOPE + "={scope}");
            vars.put("scope", OAuth2Utils.formatParameterList(accessToken.getScope()));
        }
        Map<String, Object> additionalInformation = accessToken.getAdditionalInformation();
        for (String key : additionalInformation.keySet()) {
            Object value = additionalInformation.get(key);
            if (value != null) {
                url.append("&" + key + "={extra_" + key + "}");
                vars.put("extra_" + key, value);
            }
        }
        UriTemplate template = new UriTemplate(url.toString());
        // Do not include the refresh token (even if there is one)
        return template.expand(vars).toString();
    }

    private String generateCode(AuthorizationRequest authorizationRequest, Authentication authentication)
        throws AuthenticationException {

        try {

            OAuth2Request storedOAuth2Request = getOAuth2RequestFactory().createOAuth2Request(authorizationRequest);

            OAuth2Authentication combinedAuth = new OAuth2Authentication(storedOAuth2Request, authentication);
            String code = authorizationCodeServices.createAuthorizationCode(combinedAuth);

            return code;

        } catch (OAuth2Exception e) {

            if (authorizationRequest.getState() != null) {
                e.addAdditionalInformation("state", authorizationRequest.getState());
            }

            throw e;

        }
    }

    private String getSuccessfulRedirect(AuthorizationRequest authorizationRequest, String authorizationCode) {

        if (authorizationCode == null) {
            throw new IllegalStateException("No authorization code found in the current request scope.");
        }

        UriComponentsBuilder template = UriComponentsBuilder.fromUriString(authorizationRequest.getRedirectUri());
        template.queryParam("code", authorizationCode);

        String state = authorizationRequest.getState();
        if (state != null) {
            template.queryParam("state", state);
        }

        return template.build().encode().toUriString();
    }

    private String getUnsuccessfulRedirect(AuthorizationRequest authorizationRequest, OAuth2Exception failure,
                                           boolean fragment) {

        if (authorizationRequest == null || authorizationRequest.getRedirectUri() == null) {
            // we have no redirect for the user. very sad.
            throw new UnapprovedClientAuthenticationException("Authorization failure, and no redirect URI.", failure);
        }

        UriComponentsBuilder template = UriComponentsBuilder.fromUriString(authorizationRequest.getRedirectUri());
        Map<String, String> query = new LinkedHashMap<String, String>();
        StringBuilder values = new StringBuilder();

        values.append("error={error}");
        query.put("error", failure.getOAuth2ErrorCode());

        values.append("&error_description={error_description}");
        query.put("error_description", failure.getMessage());

        if (authorizationRequest.getState() != null) {
            values.append("&state={state}");
            query.put("state", authorizationRequest.getState());
        }

        if (failure.getAdditionalInformation() != null) {
            for (Map.Entry<String, String> additionalInfo : failure.getAdditionalInformation().entrySet()) {
                values.append("&" + additionalInfo.getKey() + "={" + additionalInfo.getKey() + "}");
                query.put(additionalInfo.getKey(), additionalInfo.getValue());
            }
        }

        if (fragment) {
            template.fragment(values.toString());
        } else {
            template.query(values.toString());
        }

        return template.build().expand(query).encode().toUriString();

    }

    public void setUserApprovalPage(String userApprovalPage) {
        this.userApprovalPage = userApprovalPage;
    }

    public void setAuthorizationCodeServices(AuthorizationCodeServices authorizationCodeServices) {
        this.authorizationCodeServices = authorizationCodeServices;
    }

    public void setRedirectResolver(RedirectResolver redirectResolver) {
        this.redirectResolver = redirectResolver;
    }

    public void setUserApprovalHandler(UserApprovalHandler userApprovalHandler) {
        this.userApprovalHandler = userApprovalHandler;
    }

    public void setOAuth2RequestValidator(OAuth2RequestValidator oauth2RequestValidator) {
        this.oauth2RequestValidator = oauth2RequestValidator;
    }

    @SuppressWarnings("deprecation")
    public void setImplicitGrantService(org.springframework.security.oauth2.provider.implicit.ImplicitGrantService implicitGrantService) {
    }

    @ExceptionHandler(ClientRegistrationException.class)
    public ModelAndView handleClientRegistrationException(Exception e, ServletWebRequest webRequest) throws Exception {
        logger.info("Handling ClientRegistrationException error: " + e.getMessage());
        return handleException(new BadClientCredentialsException(), webRequest);
    }

    @ExceptionHandler(OAuth2Exception.class)
    public ModelAndView handleOAuth2Exception(OAuth2Exception e, ServletWebRequest webRequest) throws Exception {
        logger.info("Handling OAuth2 error: " + e.getSummary());
        return handleException(e, webRequest);
    }

    @ExceptionHandler(HttpSessionRequiredException.class)
    public ModelAndView handleHttpSessionRequiredException(HttpSessionRequiredException e, ServletWebRequest webRequest)
        throws Exception {
        logger.info("Handling Session required error: " + e.getMessage());
        return handleException(new AccessDeniedException("Could not obtain authorization request from session", e),
            webRequest);
    }

    private ModelAndView handleException(Exception e, ServletWebRequest webRequest) throws Exception {

        ResponseEntity<OAuth2Exception> translate = getExceptionTranslator().translate(e);
        webRequest.getResponse().setStatus(translate.getStatusCode().value());

        if (e instanceof ClientAuthenticationException || e instanceof RedirectMismatchException) {
            return new ModelAndView(errorPage, Collections.singletonMap("error", translate.getBody()));
        }

        AuthorizationRequest authorizationRequest = null;
        try {
            authorizationRequest = getAuthorizationRequestForError(webRequest);
            String requestedRedirectParam = authorizationRequest.getRequestParameters().get(OAuth2Utils.REDIRECT_URI);
            String requestedRedirect = redirectResolver.resolveRedirect(requestedRedirectParam,
                getClientDetailsService().loadClientByClientId(authorizationRequest.getClientId()));
            authorizationRequest.setRedirectUri(requestedRedirect);
            String redirect = getUnsuccessfulRedirect(authorizationRequest, translate.getBody(), authorizationRequest
                .getResponseTypes().contains("token"));
            return new ModelAndView(new RedirectView(redirect, false, true, false));
        } catch (OAuth2Exception ex) {
            // If an AuthorizationRequest cannot be created from the incoming parameters it must be
            // an error. OAuth2Exception can be handled this way. Other exceptions will generate a standard 500
            // response.
            return new ModelAndView(errorPage, Collections.singletonMap("error", translate.getBody()));
        }

    }

    private AuthorizationRequest getAuthorizationRequestForError(ServletWebRequest webRequest) {

        // If it's already there then we are in the approveOrDeny phase and we can use the saved request
        AuthorizationRequest authorizationRequest = (AuthorizationRequest) sessionAttributeStore.retrieveAttribute(
            webRequest, "authorizationRequest");
        if (authorizationRequest != null) {
            return authorizationRequest;
        }

        Map<String, String> parameters = new HashMap<String, String>();
        Map<String, String[]> map = webRequest.getParameterMap();
        for (String key : map.keySet()) {
            String[] values = map.get(key);
            if (values != null && values.length > 0) {
                parameters.put(key, values[0]);
            }
        }

        try {
            return getOAuth2RequestFactory().createAuthorizationRequest(parameters);
        } catch (Exception e) {
            return getDefaultOAuth2RequestFactory().createAuthorizationRequest(parameters);
        }

    }
}