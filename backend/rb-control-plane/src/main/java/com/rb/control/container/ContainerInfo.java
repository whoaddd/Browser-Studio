package com.rb.control.container;

public class ContainerInfo {
    private final String containerId;
    private final String cdpUrl;

    public ContainerInfo(String containerId, String cdpUrl) {
        this.containerId = containerId;
        this.cdpUrl = cdpUrl;
    }

    public String getContainerId() { return containerId; }
    public String getCdpUrl() { return cdpUrl; }
}