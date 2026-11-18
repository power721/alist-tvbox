package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;

public interface AtHostPublication {
    String instanceKey();
    JsonNode plugins();
    byte[] source(String path, boolean absentAllowed);
    void upload(String path, byte[] content);
    void refresh(int pluginId);
    void rename(int pluginId, String name, JsonNode original);
    AtSavedRuntimeTarget savedRuntime(int pluginId);
    AtRuntimeActivation activate(int pluginId, StartAtRuntimeActivationRequest input, String activationId);
}
