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
	public static final String SUCCESS = "success";
	public static final String TRIGGER_INFO_GROUP = "TriggerInfo_";
	public static final String OUTLET_GROUP = "Outlet_";
	public static final String STATUS = "Status";
	public static final String HASH = "#";
	public static final String ZERO = "0";
	public static final String OPEN_PARENTHESIS = "(";
	public static final String CLOSE_PARENTHESIS = ")";

	public static final String MONITORING_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
	public static final String ADAPTER_VERSION = "AdapterVersion";
	public static final String MONITORED_DEVICES_TOTAL = "MonitoredDevicesTotal";
	public static final String ADAPTER_BUILD_DATE = "AdapterBuildDate";
	public static final String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
	public static final String ADAPTER_UPTIME = "AdapterUptime";

	/* Response properties */
	public static final String RESPONSE_SUCCESS = "/success";
	public static final String RESPONSE_MESSAGE = "/message";

	/* Button */
	public static final String CYCLE = "Cycle";
	public static final String CYCLING = "Cycling";
}
