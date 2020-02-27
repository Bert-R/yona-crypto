/*******************************************************************************
 * Copyright (c) 2016, 2018 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.exceptions;

import java.io.Serializable;

/**
 * This exception class is used as a base for all exceptions that use an error message which is defined in the message properties
 */
public abstract class ResourceBasedException extends RuntimeException
{
	private static final long serialVersionUID = 8031973645020363969L;

	/** Holds the parameters for the exception message. */
	private final Serializable[] parameters;
	/** Holds the message id. */
	private final String messageId;

	/**
	 * Constructor.
	 * 
	 * @param statusCode The status code of the exception.
	 * @param messageId The ID of the exception in the resource bundle
	 * @param parameters The parameters for the message
	 */
	protected ResourceBasedException(String messageId, Serializable... parameters)
	{
		super(messageId);

		this.messageId = messageId;
		this.parameters = parameters;
	}

	/**
	 * Constructor.
	 * 
	 * @param statusCode The status code of the exception.
	 * @param t The cause exception
	 * @param messageId The ID of the exception in the resource bundle
	 * @param parameters The parameters for the message
	 */
	protected ResourceBasedException(Throwable t, String messageId, Serializable... parameters)
	{
		super(messageId, t);

		this.messageId = messageId;
		this.parameters = parameters;
	}

	@Override
	public String getMessage()
	{
		return getLocalizedMessage();
	}

	@Override
	public String getLocalizedMessage()
	{
		return formAlternativeMessageText();
	}

	/**
	 * This method gets the message id.
	 * 
	 * @return The message id.
	 */
	public String getMessageId()
	{
		return messageId;
	}

	private String formAlternativeMessageText()
	{
		StringBuilder sb = new StringBuilder(messageId);
		if ((parameters != null) && (parameters.length != 0))
		{
			sb.append("; parameters: ");
			appendParametersText(sb);
		}
		return sb.toString();
	}

	private void appendParametersText(StringBuilder sb)
	{
		boolean isFirst = true;
		for (Object parameter : parameters)
		{
			if (!isFirst)
			{
				sb.append(", ");
			}
			isFirst = false;
			sb.append('"');
			sb.append((parameter == null) ? "null" : parameter.toString());
			sb.append('"');
		}
	}
}
