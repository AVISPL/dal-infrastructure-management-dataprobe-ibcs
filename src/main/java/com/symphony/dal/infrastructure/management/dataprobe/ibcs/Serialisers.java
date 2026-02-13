/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * This class contains helper classes for serializing data related to control and state requests.
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public class Serialisers {

    /**
     * A class representing a reboot object, used for sending control commands to a device.
     * This includes properties for the device token, MACAddress, and reboot.
     */
    static class RebootRequest {
        private String token;
        private String mac;
        private String reboot;

        /**
         * Constructs a new {@link RebootRequest}.
         *
         * @param token    the authentication token for the device
         * @param mac      the type of control (e.g., "group", "outlet", "sequence")
         * @param reboot   1=reboot
         */
        RebootRequest(String token, String mac, String reboot) {
            this.token = token;
            this.mac = mac;
            this.reboot = reboot;
        }

        public String getToken() { return token; }
        public String getMac() { return mac; }
        public String getReboot() { return reboot; }

        public void setToken(String token) { this.token = token; }
        public void setMac(String mac) { this.mac = mac; }
        public void setReboot(String reboot) { this.reboot = reboot; }
    }

    /**
     * A class representing a control object, used for sending control commands to a device.
     * This includes properties for the device token, MACAddress, outletIndex, and controlType.
     */
    static class ControlObject{
        private String token, mac, control;
        private String[] outlet;

        /**
         * Constructs a new {@link ControlObject}.
         *
         * @param _token    the authentication token for the device
         * @param _MACAddress  the type of control (e.g., "group", "outlet", "sequence")
         * @param _outletIndex  the command to be sent (e.g., "on", "off", "cycle")
         * @param _controlType  an array of outlets to be controlled
         */
        ControlObject(String _token,String _MACAddress,String[] _outletIndex,String _controlType){
            token = _token;
            mac = _MACAddress;
            outlet = _outletIndex;
            control = _controlType;
        }
        public String getToken() {return token;}
        public String getMac() {return mac;}
        public String[] getOutlet() {return outlet;}
        public String getControl() {return control;}
        public void setToken(String token) {this.token = token;}
        public void setMac(String mac) {this.mac = mac;}
        public void setOutlet(String[] outlet) {this.outlet = outlet;}
        public void setControl(String control) {this.control = control;}
    }

    /**
     * A class representing a state request object, used for retrieving the state of outlets and groups.
     * This includes properties for the device token, outlets, and groups.
     */
    static class StateRequestObject{
        private String token;
        private String[] outlets, groups;

        /**
         * Constructs a new {@link StateRequestObject}.
         *
         * @param _token    the authentication token for the device
         * @param _outlets  an array of outlets to be retrieved
         * @param _groups   an array of groups to be retrieved
         */
        StateRequestObject(String _token,String[] _outlets, String[] _groups){
            token = _token;
            outlets = _outlets;
            groups = _groups;
        }
        public String getToken() {return token;}
        public String[] getGroups() {return groups;}
        public String[] getOutlets() {return outlets;}
        public void setToken(String token) {this.token = token;}
        public void setOutlets(String[] outlets) {this.outlets = outlets;}
        public void setGroups(String[] groups) {this.groups = groups;}
    }

    /**
     * Request object for iBoot-G2 configuration "set" API.
     * Matches the structure:
     * {
     *   "token": "...",
     *   "mac": "...",
     *   "device": { ... }
     * }
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class G2ConfigurationRequest {
        private String token;
        private String mac;
        private DeviceConfig device;

        G2ConfigurationRequest(String token, String mac, DeviceConfig device) {
            this.token = token;
            this.mac = mac;
            this.device = device;
        }

        public String getToken() { return token; }
        public String getMac() { return mac; }
        public DeviceConfig getDevice() { return device; }
        public void setToken(String token) { this.token = token; }
        public void setMac(String mac) { this.mac = mac; }
        public void setDevice(DeviceConfig device) { this.device = device; }
    }

    /**
     * Inner object representing the "device" section of the G2 configuration payload.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class DeviceConfig {
        private String location;
        private String cycleTime;
        private String disableOff;
        private String initialState;
        private String upgradeEnable;
        private String autoLogout;

        /**
         * Constructs a new {@link DeviceConfig} for an iBoot-G2.
         *
         * @param location     the device location name
         * @param cycleTime    the cycle time in seconds
         * @param disableOff   flag to disable OFF state ("0" or "1")
         * @param initialState initial state (e.g., "last")
         * @param upgradeEnable firmware upgrade enable flag ("0" or "1")
         * @param autoLogout   auto logout timeout in minutes
         */
        DeviceConfig(String location, String cycleTime, String disableOff, String initialState,
            String upgradeEnable, String autoLogout) {
            this.location = location;
            this.cycleTime = cycleTime;
            this.disableOff = disableOff;
            this.initialState = initialState;
            this.upgradeEnable = upgradeEnable;
            this.autoLogout = autoLogout;
        }

        DeviceConfig() {}

        public String getLocation() { return location; }
        public String getCycleTime() { return cycleTime; }
        public String getDisableOff() { return disableOff; }
        public String getInitialState() { return initialState; }
        public String getUpgradeEnable() { return upgradeEnable; }
        public String getAutoLogout() { return autoLogout; }

        public void setLocation(String location) { this.location = location; }
        public void setCycleTime(String cycleTime) { this.cycleTime = cycleTime; }
        public void setDisableOff(String disableOff) { this.disableOff = disableOff; }
        public void setInitialState(String initialState) { this.initialState = initialState; }
        public void setUpgradeEnable(String upgradeEnable) { this.upgradeEnable = upgradeEnable; }
        public void setAutoLogout(String autoLogout) { this.autoLogout = autoLogout; }
    }
}
