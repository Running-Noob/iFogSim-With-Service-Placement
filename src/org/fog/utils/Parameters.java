package org.fog.utils;

/**
 * @author fzy
 * @date 2024/10/27 17:40
 * 实验相关的一系列参数
 */
public class Parameters {
    /*
     * Application Parameters
     * */

    /*
     * FogDevice Parameters
     * */

    // 延迟参数
    public static final double proxyServerToCloudUplinkLatency = 100;
    public static final double deptToproxyServerUplinkLatency = 4;
    public static final double mobileToDeptUplinkLatency = 2;
    public static final double sensorToMobileUplinkLatency = 6;
    public static final double mobileToActuatorUplinkLatency = 1;

    /*
     * Controller Parameters
     * */
}
