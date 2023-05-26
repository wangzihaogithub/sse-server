package com.github.sseserver.controller;

import org.springframework.context.annotation.Configuration;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Configuration
public class ServletFilters {
    //    @Component
    @WebFilter(filterName = "c1", urlPatterns = "/my-sse/connect**", asyncSupported = true)
    public static class C1 implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpServletRequest = (HttpServletRequest) request;
            System.out.println("/my-sse/connect** = " + httpServletRequest.getRequestURI());
            chain.doFilter(request, response);
        }
    }

    //    @Component
    @WebFilter(filterName = "c2", urlPatterns = "/my-sse/connect*", asyncSupported = true)
    public static class C2 implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpServletRequest = (HttpServletRequest) request;
            System.out.println("/my-sse/connect* = " + httpServletRequest.getRequestURI());
            chain.doFilter(request, response);
        }
    }

    //    @Component
    @WebFilter(filterName = "c3", urlPatterns = "/my-sse/connect", asyncSupported = true)
    public static class C3 implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpServletRequest = (HttpServletRequest) request;
            System.out.println("/my-sse/connect = " + httpServletRequest.getRequestURI());
            chain.doFilter(request, response);
        }
    }

    //    @Component
    @WebFilter(filterName = "c4", urlPatterns = "/my-sse/connect/*", asyncSupported = true)
    public static class C4 implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpServletRequest = (HttpServletRequest) request;
            System.out.println("/my-sse/connect/* = " + httpServletRequest.getRequestURI());
            chain.doFilter(request, response);
        }
    }

}
