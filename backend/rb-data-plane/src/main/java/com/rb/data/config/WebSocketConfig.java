package com.rb.data.config;


import com.rb.common.jwt.JwtVerifier;
import com.rb.data.ws.AuthConfigurator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    @Autowired
    public void bindJwtVerifier(JwtVerifier verifier) {
        AuthConfigurator.setJwtVerifier(verifier);
    }
}