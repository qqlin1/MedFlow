package com.qqlin.medflow.shared.web.filter;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String TRACE_ID_HEADER="X-Trace-Id";
    public static final String MDC_KEY="traceId";
    public static final Logger LOGGER= LoggerFactory.getLogger(RequestTraceFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
    String traceId= UUID.randomUUID().toString();
        MDC.put(MDC_KEY,traceId);
        try{
            response.setHeader(TRACE_ID_HEADER,traceId);
            LOGGER.info("HTTP {} {}",
                    request.getMethod(),
                    request.getRequestURI());
        filterChain.doFilter(request,response);
        }finally {
            MDC.remove(MDC_KEY);
        }
    }
}
