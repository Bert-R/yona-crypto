/*******************************************************************************
 * Copyright (c) 2020 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.crypto.seckey;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter
public class ByteArrayFieldEncryptor implements AttributeConverter<byte[], byte[]>
{
	@Override
	public byte[] convertToDatabaseColumn(byte[] attribute)
	{
		return (attribute == null) ? null : SecretKeyUtil.encryptBytes(attribute);
	}

	@Override
	public byte[] convertToEntityAttribute(byte[] dbData)
	{
		try
		{
			if (dbData == null)
			{
				return null;
			}

			return SecretKeyUtil.decryptBytes(dbData);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
