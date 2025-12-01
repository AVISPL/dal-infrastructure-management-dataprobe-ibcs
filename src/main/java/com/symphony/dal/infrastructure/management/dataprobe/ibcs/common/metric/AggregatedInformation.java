/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric;

/**
 * Enum AggregatedInformation represents various pieces of aggregated information about a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public enum AggregatedInformation {
	MAC("MACAddress", "mac", ""),
	NAME("Name", "name", ""),
	MODEL("Model", "model", ""),
	LOCATION("Location", "location", ""),
	LAST_CONTACT("LastContact", "lastContact", ""),
	IPADDRESS("IPAddress", "ip", ""),
	IS_DEVICE_ONLINE("DeviceStatus", "online", ""),
	MANAGE_LINK("ManageLink", "message", ""),
	;

	private final String name;
	private final String field;
	private final String group;

	/**
	 * Constructor for AggregatedInformation.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	AggregatedInformation(String name, String field, String group) {
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
