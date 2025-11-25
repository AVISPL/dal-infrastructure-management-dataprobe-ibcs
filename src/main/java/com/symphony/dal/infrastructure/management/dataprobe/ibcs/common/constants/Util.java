/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.constants;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.symphony.dal.infrastructure.management.dataprobe.ibcs.common.DataprobeConstant;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.dal.util.StringUtils;

/**
 * Utility class for the adapter. Which includes helper methods.
 *
 * @author Harry / Symphony Dev Team
 * @since 1.0.0
 */
public class Util {

	private static final ZoneId ZONE_HCM = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final DateTimeFormatter FORMATTER =
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZONE_HCM);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Add addAdvancedControlProperties if advancedControllableProperties different empty
	 *
	 * @param advancedControllableProperties advancedControllableProperties is the list that store all controllable properties
	 * @param stats store all statistics
	 * @param property the property is item advancedControllableProperties
	 * @throws IllegalStateException when exception occur
	 */
	public static void addAdvancedControlProperties(List<AdvancedControllableProperty> advancedControllableProperties, Map<String, String> stats, AdvancedControllableProperty property, String value) {
		if (property != null) {
			advancedControllableProperties.removeIf(controllableProperty -> controllableProperty.getName().equals(property.getName()));

			String propertyValue = StringUtils.isNotNullOrEmpty(value) && !DataprobeConstant.NONE.equals(value) ? value : DataprobeConstant.EMPTY;
			stats.put(property.getName(), propertyValue);
			advancedControllableProperties.add(property);
		}
	}

	/**
	 * Add device control to controls list, but make sure it's not duplicated.
	 * Properties are searched by name, if there's any match - existing property is removed and replaced with the newer one
	 *
	 * @param controllableProperties full list of properties
	 * @param controllableProperty property to add
	 * */
	public static void addOrUpdateDeviceControl(List<AdvancedControllableProperty> controllableProperties, AdvancedControllableProperty controllableProperty) {
		controllableProperties.removeIf(acp -> Objects.equals(controllableProperty.getName(), acp.getName()));
		controllableProperties.add(controllableProperty);
	}

	/**
	 * capitalize the first character of the string
	 *
	 * @param input input string
	 * @return string after fix
	 */
	public static String uppercaseFirstCharacter(String input) {
		char firstChar = input.charAt(0);
		return Character.toUpperCase(firstChar) + input.substring(1);
	}

	/**
	 * check value is null or empty
	 *
	 * @param value input value
	 * @return value after checking
	 */
	public static String getDefaultValueForNullData(String value) {
		return StringUtils.isNotNullOrEmpty(value) && !"null".equalsIgnoreCase(value) ? uppercaseFirstCharacter(value) : DataprobeConstant.NOT_AVAILABLE;
	}

	/**
	 * Formats a Unix epoch timestamp as a UTC date-time string ("yyyy/MM/dd HH:mm:ss").
	 * @param epochInput epoch timestamp in seconds or milliseconds
	 * @return formatted UTC date-time string (e.g., "2025/10/02 02:33:21")
	 */
	public static String formatEpochUtc(long epochInput) {
		long epochMillis = (epochInput < 10_000_000_000L) ? epochInput * 1000L : epochInput;
		return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
	}

	public static String authBody(String user, String pass, String interval, String scale, String revoke)
			throws JsonProcessingException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("username", user);
		root.put("password", pass);

		if (!isBlank(revoke)) {
			root.put("revoke", revoke);
		} else if (!isBlank(interval) && !isBlank(scale)) {
			ObjectNode timeout = MAPPER.createObjectNode();
			timeout.put("interval", interval);
			timeout.put("scale", scale);
			root.set("timeout", timeout);
		}
		return MAPPER.writeValueAsString(root);
	}

	public static String requestBody(String token, Set<String> allTypes, String location, String mac) throws JsonProcessingException {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("token", token);

		if (!isBlank(mac)) {
			root.put("mac", mac);
			return MAPPER.writeValueAsString(root);
		}

		ArrayNode allArray = MAPPER.createArrayNode();
		if (allTypes == null || allTypes.isEmpty()) {
			allArray.add("");
		} else {
			for (String t : allTypes) {
				allArray.add(t == null ? DataprobeConstant.EMPTY : t);
			}
		}

		if (!isBlank(location)) {
			root.put("location", location);
			root.set("all", allArray);
			return MAPPER.writeValueAsString(root);
		}

		root.set("all", allArray);
		return MAPPER.writeValueAsString(root);
	}


	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	/**
	 * Uptime is received in seconds, need to normalize it and make it human-readable, like
	 * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
	 * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
	 * We don't need to add a segment of time if it's 0.
	 *
	 * @param uptimeSeconds value in seconds
	 * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
	 */
	public static String normalizeUptime(long uptimeSeconds) {
		StringBuilder normalizedUptime = new StringBuilder();

		long seconds = uptimeSeconds % 60;
		long minutes = uptimeSeconds % 3600 / 60;
		long hours = uptimeSeconds % 86400 / 3600;
		long days = uptimeSeconds / 86400;

		if (days > 0) {
			normalizedUptime.append(days).append(" day(s) ");
		}
		if (hours > 0) {
			normalizedUptime.append(hours).append(" hour(s) ");
		}
		if (minutes > 0) {
			normalizedUptime.append(minutes).append(" minute(s) ");
		}
		if (seconds > 0) {
			normalizedUptime.append(seconds).append(" second(s)");
		}
		return normalizedUptime.toString().trim();
	}
}
