/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.metric;

/**
 * Enum ConfigurationNetwork represents various pieces of configuration network of a device.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public enum ConfigurationNetwork {
	IP_MODE("IPMode", "ipMode", "Network"),
	IP_ADDRESS("IPAddress", "ipAddress", "Network"),
	SUBNET_MASK("SubnetMask", "subnetMask", "Network"),
	GATEWAY("Gateway", "gateway", "Network"),
	DNS("DNS", "dns", "Network"),
	;

	private final String name;
	private final String field;
	private final String group;

	/**
	 * Constructor for ConfigurationNetwork.
	 *
	 * @param name The name representing the system information category.
	 * @param group The group associated with the category.
	 */
	ConfigurationNetwork(String name, String field, String group) {
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
