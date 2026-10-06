package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

/**
 * Default NoOp logger binder implementation to satisfy minimum requirements.
 *
 * @author patrick.reinhart
 */
public enum StaticLoggerBinder implements ILoggerFactory {
	INSTANCE;

	/** SLF4J API version this binding is compiled against (read by SLF4J 1.7 at bind time). */
	public static String REQUESTED_API_VERSION = "1.7.36"; // not final, as required by SLF4J

	public static StaticLoggerBinder getSingleton() {
		return StaticLoggerBinder.INSTANCE;
	}

	public ILoggerFactory getLoggerFactory() {
		return this;
	}

	public String getLoggerFactoryClassStr() {
		return StaticLoggerBinder.class.getName();
	}

	@Override
	public Logger getLogger(String name) {
		return NOPLogger.NOP_LOGGER;
	}
}
