package org.fog.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleMapping {
    // Map<雾设备，放置在雾设备上的应用程序模块的集合>
    protected Map<String, List<String>> moduleMapping;

    public static ModuleMapping createModuleMapping() {
        return new ModuleMapping();
    }

    public Map<String, List<String>> getModuleMapping() {
        return moduleMapping;
    }

    public void setModuleMapping(Map<String, List<String>> moduleMapping) {
        this.moduleMapping = moduleMapping;
    }

    protected ModuleMapping() {
        setModuleMapping(new HashMap<String, List<String>>());
    }

    /**
     * Add <b>instanceCount</b> number of instances of module <b>moduleName</b> to <b>device deviceName</b>
     *
     * @param moduleName
     * @param deviceName
     * @param instanceCount
     */
    // 将雾设备和应用模块之间的放置关系建立起来
    public void addModuleToDevice(String moduleName, String deviceName) {
        if (!getModuleMapping().containsKey(deviceName))
            getModuleMapping().put(deviceName, new ArrayList<String>());
        if (!getModuleMapping().get(deviceName).contains(moduleName))
            getModuleMapping().get(deviceName).add(moduleName);
    }
}
