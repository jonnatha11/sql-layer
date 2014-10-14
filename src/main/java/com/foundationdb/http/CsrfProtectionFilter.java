/**
 * Copyright (C) 2009-2014 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Priorities;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * This is similar to the builtin CsrfProtectionFilter, but does not allow GET requests either, because
 * those can execute any SQL query.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CsrfProtectionFilter implements javax.servlet.Filter {
    private static final Logger logger = LoggerFactory.getLogger(CsrfProtectionFilter.class);

    public static final String REFERER_HEADER = "Referer";
    public static final String ALLOWED_REFERERS_PARAM = "AllowedReferersInitParam";

    private List<URI> allowedReferers;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        String allowedReferersConfigProperty = filterConfig.getInitParameter(ALLOWED_REFERERS_PARAM);
        allowedReferers = parseAllowedReferers(allowedReferersConfigProperty);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest) {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            String referer = request.getHeader(REFERER_HEADER);
            if (!isAllowedUri(allowedReferers, referer)) {
                logger.debug("CSRF attempt blocked due to invalid referer uri; Request:{} Referer:{}",
                        request.getRequestURI(),
                        referer);
                ((HttpServletResponse)servletResponse).sendError(400,
                        "CSRF attack prevented. For legit usage see server.properties");
            }
        } else {
            logger.error("Unexpected type of request: {} -- {}", servletRequest.getClass(), servletRequest);
        }
    }

    @Override
    public void destroy() {

    }

    public static List<URI> parseAllowedReferers(String allowedReferersConfigProperty) {
        if (allowedReferersConfigProperty == null || allowedReferersConfigProperty.isEmpty()) {
            throw new IllegalArgumentException("Invalid List of allowed csrf referers must not be null or empty");
        }
        String[] split = allowedReferersConfigProperty.split("\\,");
        List<URI> allowedReferers = new ArrayList<>();
        for (String allowedReferer : split) {
            if (allowedReferer == null || allowedReferer.isEmpty()) {
                continue;
            } else {
                if (allowedReferer.contains("*")) {
                    throw new IllegalArgumentException("Allowed referers do not support regexs (*): " + allowedReferer);
                }
                URI uri;
                try {
                    uri = URI.create(allowedReferer);
                } catch (NullPointerException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid URI in list of csrf referers: " + allowedReferer, e);
                }
                if (uri == null) {
                    throw new IllegalArgumentException("Invalid URI in list of csrf referers: " + allowedReferer);
                }
                if (uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("Allowed referers do not support user information: " + allowedReferer);
                }
                if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
                    throw new IllegalArgumentException("Allowed referers do not support restricting by path: " + allowedReferer);
                }
                allowedReferers.add(uri);
            }
        }
        if (allowedReferers.isEmpty()) {
            throw new IllegalArgumentException("Invalid List of allowed csrf referers must not be null or empty");
        }
        return allowedReferers;
    }

    public static boolean isAllowedUri(List<URI> allowedReferers, String referer) {
        if (referer == null || referer.isEmpty()) {
            return false;
        }
        URI refererUri = URI.create(referer);
        for (URI uri : allowedReferers) {
            if (uri.getScheme().equals(refererUri.getScheme()) &&
                    uri.getHost().equals(refererUri.getHost()) &&
                    uri.getPort() == refererUri.getPort()) {
                return true;
            }
        }
        return false;
    }
}
