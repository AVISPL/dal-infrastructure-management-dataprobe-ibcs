/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric;

/**
 * Enum ConfigurationAdvancedNetwork represents various pieces of configuration of a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public enum ConfigurationAdvancedNetwork {
	HTTP_PORT("HTTPPort", "httpPort", "AdvancedNetwork"),
	LINK_BACK_URL("LinkBackURL", "linkbackUrl", "AdvancedNetwork"),
	TELNET_PORT("TelnetPort", "telnetPort", "AdvancedNetwork"),
	DXP_PORT("DXPPort", "dxpPort", "AdvancedNetwork"),
	CLOUD_ENABLED("CloudEnabled", "cloudEnabled", "AdvancedNetwork"),
	ENABLE_TIME_SERVER("EnableTimeServer", "enableTimeServer", "AdvancedNetwork"),
	TIME_SERVER_ADDRESS("TimeServerAddress", "timeServerAddress", "AdvancedNetwork"),
	TIME_ZONE("TimeZone", "timezone", "AdvancedNetwork"),

	DST_ENABLED("DSTEnabled", "enableDst", "AdvancedNetwork"),
	DST_START_WEEK("DSTStartWeek", "dstStartWeek", "AdvancedNetwork"),
	DST_START_DAY("DSTStartDay", "dstStartDay", "AdvancedNetwork"),
	DST_START_MONTH("DSTStartMonth", "dstStartMonth", "AdvancedNetwork"),
	DST_START_TIME("DSTStartTime", "dstStartTime", "AdvancedNetwork"),

	DST_STOP_WEEK("DSTStopWeek", "dstStopWeek", "AdvancedNetwork"),
	DST_STOP_DAY("DSTStopDay", "dstStopDay", "AdvancedNetwork"),
	DST_STOP_MONTH("DSTStopMonth", "dstStopMonth", "AdvancedNetwork"),
	DST_STOP_TIME("DSTStopTime", "dstStopTime", "AdvancedNetwork"),
	;

	private final String name;
	private final String field;
	private final String group;

	/**
	 * Constructor for ConfigurationAdvancedNetwork.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	ConfigurationAdvancedNetwork(String name, String field, String group) {
		this.name = name;
		this.field = field;
		this.group = group;
	}

	/**
	 * Retrieves {@link #name}
	 *
	 * @return value of {@link #name}
	 */
	public String getName() {
		return name;
	}

	/**
	 * Retrieves {@link #field}
	 *
	 * @return value of {@link #field}
	 */
	public String getField() {
		return field;
	}

	/**
	 * Retrieves {@link #group}
	 *
	 * @return value of {@link #group}
	 */
	public String getGroup() {
		return group;
	}
}
