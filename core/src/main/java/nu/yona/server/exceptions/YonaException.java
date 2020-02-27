/*******************************************************************************
 * Copyright (c) 2015, 2019 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.exceptions;

import java.io.Serializable;

/**
 * Generic exception class for any Yona exception.
 */
public class YonaException extends ResourceBasedException
{
	private static final long serialVersionUID = 6332689175661269736L;

	protected YonaException(String messageId, Serializable... parameters)
	{
		super(messageId, parameters);
	}

	protected YonaException(Throwable t, String messageId, Serializable... parameters)
	{
		super(t, messageId, parameters);
	}

	public static YonaException unexpected(Throwable e)
	{
		return new YonaException(e, "error.unexpected");
	}

	public static YonaException illegalState(String description)
	{
		return new YonaException("error.illegal.state", description);
	}
}
