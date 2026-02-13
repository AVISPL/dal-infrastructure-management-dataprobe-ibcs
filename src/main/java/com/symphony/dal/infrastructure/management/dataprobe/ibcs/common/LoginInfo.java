/*
 *  Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */

package com.symphony.dal.infrastructure.management.dataprobe.ibcs.common;

/**
 * LoginInfo class represents information about a login session.
 *
 * @author Harry / Symphony Dev Team<br>
 * @since 1.0.0
 */
public class LoginInfo {
	private long loginDateTime = 0;
	private String token;

	/**
	 * Create an instance of LoginInfo
	 */
	public LoginInfo() {
		this.loginDateTime = 0;
	}

	/**
	 * Retrieves {@code {@link #loginDateTime}}
	 *
	 * @return value of {@link #loginDateTime}
	 */
	public long getLoginDateTime() {
		return loginDateTime;
	}

	/**
	 * Sets {@code loginDateTime}
	 *
	 * @param loginDateTime the {@code long} field
	 */
	public void setLoginDateTime(long loginDateTime) {
		this.loginDateTime = loginDateTime;
	}

	/**
	 * Retrieves {@code {@link #token}}
	 *
	 * @return value of {@link #token}
	 */
	public String getToken() {
		return token;
	}

	/**
	 * Sets {@code token}
	 *
	 * @param token the {@code java.lang.String} field
	 */
	public void setToken(String token) {
		this.token = token;
	}

	/**
	 * Check token expiry time
	 * Token is timeout when elapsed > 60 min
	 *
	 * @return boolean
	 */
	public boolean isTimeout() {
		return ((System.currentTimeMillis() - loginDateTime) / 60000) > 60;
	}
}
