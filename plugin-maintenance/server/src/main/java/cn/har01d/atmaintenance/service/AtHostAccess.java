package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import java.util.List;

/** Implemented only by the AT host; never opens an HTTP session or executes a spider. */
public interface AtHostAccess {
    AtConnection connection();
    List<AtPluginSummary> plugins();
    byte[] readPythonSource(int pluginId);
    AtRuntimeIdentity runtimeIdentity(int pluginId);
}
