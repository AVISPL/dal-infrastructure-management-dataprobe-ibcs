/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common;

/**
 * Enum representing various the constant.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public class DataprobeConstant {
	public static final String BASE_URL = "services/v4.1";
	public static final String TIMEOUT_INTERVAL = "60";
	public static final String TIMEOUT_SCALE = "minutes";
	public static final String RETRIEVE_MANAGE_LINK = "{\"token\":\"%s\", \"mac\":\"%s\"}";
	public static final String DEVICES = "devices";
	public static final String NONE = "None";
	public static final String NOT_AVAILABLE = "N/A";
	public static final String EMPTY = "";
	public static final String TRUE = "true";
	public static final String FALSE = "false";
	public static final String ON = "On";
	public static final String OFF = "Off";
	public static final String SUCCESS = "success";
	public static final String TRIGGER_INFO_GROUP = "TriggerInfo_";
	public static final String OUTLET_GROUP = "Outlet_";
	public static final String CONTROL = "Control";
	public static final String CONFIGURATION_LOCATION = "Configuration#Location";

	public static final String ERROR_CONTEXT_CONTROL = "control device";
	public static final String ERROR_CONTEXT_REBOOT = "reboot device";
	public static final String STATUS = "Status";
	public static final String REBOOT = "Reboot";
	public static final String HASH = "#";
	public static final String COMMA = ",";
	public static final String COMMA_SPACE = ", ";
	public static final String ZERO = "0";
	public static final String ONE = "1";
	public static final String UNDER_SCORE = "_";
	public static final String OPEN_PARENTHESIS = "(";
	public static final String CLOSE_PARENTHESIS = ")";

	public static final String MONITORING_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
	public static final String ADAPTER_VERSION = "AdapterVersion";
	public static final String MONITORED_DEVICES_TOTAL = "MonitoredDevicesTotal";
	public static final String ADAPTER_BUILD_DATE = "AdapterBuildDate";
	public static final String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
	public static final String ADAPTER_UPTIME = "AdapterUptime";
	public static final String SYSTEM_MONITORING_CYCLE = "MonitoringCycleInterval(min)";
	public static final String ACTIVE_PROPERTY_GROUPS = "ActivePropertyGroups";

	/* Response properties */
	public static final String RESPONSE_SUCCESS = "/success";
	public static final String RESPONSE_MESSAGE = "/message";

	/* Button */
	public static final String CYCLE = "Cycle";
	public static final String CYCLING = "Cycling";

	/* Group filter */
	public static final String GENERAL = "General";
	public static final String ADVANCED_NETWORK = "AdvancedNetwork";
	public static final String AUTOPING = "Autoping";
	public static final String CONFIGURATION = "Configuration";
	public static final String OUTLET = "Outlet";
	public static final String NETWORK = "Network";
	public static final String ALL = "All";
}
