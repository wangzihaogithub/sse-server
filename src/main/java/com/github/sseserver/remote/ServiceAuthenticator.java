package com.github.sseserver.remote;

import com.sun.net.httpserver.HttpPrincipal;

public interface ServiceAuthenticator {

    HttpPrincipal login(String authorization);
}
