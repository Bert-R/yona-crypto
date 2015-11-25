/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.subscriptions.service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.transaction.Transactional;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import nu.yona.server.crypto.CryptoSession;
import nu.yona.server.crypto.CryptoUtil;
import nu.yona.server.exceptions.InvalidDataException;
import nu.yona.server.exceptions.SignInValidationException;
import nu.yona.server.messaging.entities.MessageSource;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.sms.SmsService;
import nu.yona.server.subscriptions.entities.Buddy;
import nu.yona.server.subscriptions.entities.NewDeviceRequest;
import nu.yona.server.subscriptions.entities.User;
import nu.yona.server.subscriptions.entities.UserAnonymized;

@Service
public class UserService
{
	/** Holds the regex to validate a valid phone number. Start with a '+' sign followed by only numbers */
	private static Pattern REGEX_PHONE = Pattern.compile("^\\+[1-9][0-9]+$");

	private SecureRandom random = new SecureRandom();

	@Autowired
	YonaProperties properties;
	@Autowired
	private SmsService smsService;
	@Value("${yona.sms.sign-in.confirmation.message}")
	private String signInConfirmationMessage;

	// TODO: Do we need this? Currently unused.
	@Transactional
	public UserDTO getUser(String mobileNumber)
	{
		if (mobileNumber == null)
		{
			throw new IllegalArgumentException("mobileNumber cannot be null");
		}

		User userEntity = findUserByMobileNumber(mobileNumber);
		return UserDTO.createInstanceWithPrivateData(userEntity);
	}

	@Transactional
	public boolean canAccessPrivateData(UUID id)
	{
		return getEntityByID(id).canAccessPrivateData();
	}

	@Transactional
	public UserDTO getPublicUser(UUID id)
	{
		return UserDTO.createInstance(getEntityByID(id));
	}

	@Transactional
	public UserDTO getPrivateUser(UUID id)
	{
		return UserDTO.createInstanceWithPrivateData(getEntityByID(id));
	}

	@Transactional
	public UserDTO addUser(UserDTO userResource)
	{
		validateUserFields(userResource);

		User userEntity = userResource.createUserEntity();
		userEntity.setConfirmationCode(createConfirmationCode());
		userEntity = User.getRepository().save(userEntity);
		UserDTO userDTO = UserDTO.createInstanceWithPrivateData(userEntity);
		if (properties.getIsRunningInTestMode())
		{
			userDTO.setConfirmationCode(userEntity.getConfirmationCode());
		}
		else
		{
			sendSignConfirmationMessage(userEntity);
		}
		return userDTO;
	}

	private String createConfirmationCode()
	{
		SecureRandom random = CryptoUtil.getSecureRandomInstance();
		return "" + (random.nextInt(90000) + 10000); // Generate 5 digits in range 10000 - 99999.
	}

	private void sendSignConfirmationMessage(User userEntity)
	{
		String message = MessageFormat.format(signInConfirmationMessage, userEntity.getConfirmationCode());
		smsService.send(userEntity.getMobileNumber(), message);
	}

	@Transactional
	public void confirmSignIn(UUID userID, String code)
	{
		User userEntity = getEntityByID(userID);

		if (userEntity.getConfirmationCode() == null)
		{
			throw SignInValidationException.signInCodeNotSet();
		}

		if (!userEntity.getConfirmationCode().equals(code))
		{
			throw SignInValidationException.signInCodeMismatch();
		}

		if (userEntity.getStatus() != User.Status.UNCONFIRMED)
		{
			throw SignInValidationException.userCannotBeActivated();
		}

		userEntity.setConfirmationCode(null);
		userEntity.setStatus(User.Status.ACTIVE);
		User.getRepository().save(userEntity);
	}

	@Autowired
	UserServiceTempEncryptionContextExecutor tempEncryptionContextExecutor;

	@Transactional
	public User addUserCreatedOnBuddyRequest(UserDTO buddyUserResource, String tempPassword)
	{
		UUID savedUserID = CryptoSession.execute(Optional.of(tempPassword), null,
				() -> tempEncryptionContextExecutor.addUserCreatedOnBuddyRequest(buddyUserResource).getID());
		return getEntityByID(savedUserID);
	}

	@Transactional
	public UserDTO updateUser(UUID id, UserDTO userResource)
	{
		User originalUserEntity = getEntityByID(id);
		if (originalUserEntity.isCreatedOnBuddyRequest())
		{
			// security check: should not be able to update a user created on buddy request with its temp password
			throw new IllegalArgumentException("User is created on buddy request, use other method");
		}
		return UserDTO.createInstanceWithPrivateData(User.getRepository().save(userResource.updateUser(originalUserEntity)));
	}

	static User findUserByMobileNumber(String mobileNumber)
	{
		User userEntity;
		userEntity = User.getRepository().findByMobileNumber(mobileNumber);
		if (userEntity == null)
		{
			throw UserServiceException.notFoundByMobileNumber(mobileNumber);
		}
		return userEntity;
	}

	@Transactional
	public UserDTO updateUserCreatedOnBuddyRequest(UUID id, String tempPassword, UserDTO userResource)
	{
		User originalUserEntity = getEntityByID(id);
		if (!originalUserEntity.isCreatedOnBuddyRequest())
		{
			// security check: should not be able to replace the password on an existing user
			throw new IllegalArgumentException("User is not created on buddy request");
		}
		EncryptedUserData retrievedEntitySet = retrieveUserEncryptedData(originalUserEntity, tempPassword);
		return saveUserEncryptedDataWithNewPassword(retrievedEntitySet, userResource);
	}

	private EncryptedUserData retrieveUserEncryptedData(User originalUserEntity, String password)
	{
		return CryptoSession.execute(Optional.of(password), () -> canAccessPrivateData(originalUserEntity.getID()),
				() -> tempEncryptionContextExecutor.retrieveUserEncryptedData(originalUserEntity));
	}

	private UserDTO saveUserEncryptedDataWithNewPassword(EncryptedUserData retrievedEntitySet, UserDTO userResource)
	{
		// touch and save all user related data containing encryption
		// see architecture overview for which classes contain encrypted data
		// (this could also be achieved with very complex reflection)
		retrievedEntitySet.userEntity.getBuddies().forEach(buddy -> Buddy.getRepository().save(buddy.touch()));
		MessageSource.getRepository().save(retrievedEntitySet.namedMessageSource.touch());
		MessageSource.getRepository().save(retrievedEntitySet.anonymousMessageSource.touch());
		userResource.updateUser(retrievedEntitySet.userEntity);
		retrievedEntitySet.userEntity.unsetIsCreatedOnBuddyRequest();
		retrievedEntitySet.userEntity.touch();
		return UserDTO.createInstanceWithPrivateData(User.getRepository().save(retrievedEntitySet.userEntity));
	}

	public void deleteUser(Optional<String> password, UUID id)
	{

		User.getRepository().delete(id);
	}

	private User getEntityByID(UUID id)
	{
		User entity = User.getRepository().findOne(id);
		if (entity == null)
		{
			throw UserServiceException.notFoundByID(id);
		}
		return entity;
	}

	private void validateUserFields(UserDTO userResource)
	{
		if (StringUtils.isBlank(userResource.getFirstName()))
		{
			throw InvalidDataException.blankFirstName();
		}

		if (StringUtils.isBlank(userResource.getLastName()))
		{
			throw InvalidDataException.blankLastName();
		}

		if (StringUtils.isBlank(userResource.getMobileNumber()))
		{
			throw InvalidDataException.blankMobileNumber();
		}

		if (!REGEX_PHONE.matcher(userResource.getMobileNumber()).matches())
		{
			throw InvalidDataException.invalidMobileNumber(userResource.getMobileNumber());
		}
	}

	public static String getPassword(Optional<String> password)
	{
		return password.orElseThrow(() -> UserServiceException.missingPasswordHeader());
	}

	public void addBuddy(UserDTO user, BuddyDTO buddy)
	{
		User userEntity = getEntityByID(user.getID());
		Buddy buddyEntity = Buddy.getRepository().findOne(buddy.getID());
		userEntity.addBuddy(buddyEntity);
		User.getRepository().save(userEntity);
	}

	public String generateTempPassword()
	{
		// see http://stackoverflow.com/questions/41107/how-to-generate-a-random-alpha-numeric-string
		return new BigInteger(130, random).toString(32);
	}

	@Transactional
	public NewDeviceRequestDTO setNewDeviceRequestForUser(UUID userID, String userPassword, String userSecret)
	{
		User userEntity = getEntityByID(userID);
		NewDeviceRequest newDeviceRequestEntity = NewDeviceRequest.createInstance(userPassword);
		newDeviceRequestEntity.encryptUserPassword(userSecret);
		boolean isUpdatingExistingRequest = userEntity.getNewDeviceRequest() != null;
		userEntity.setNewDeviceRequest(newDeviceRequestEntity);
		return NewDeviceRequestDTO.createInstance(User.getRepository().save(userEntity).getNewDeviceRequest(),
				isUpdatingExistingRequest);
	}

	@Transactional
	public NewDeviceRequestDTO getNewDeviceRequestForUser(UUID userID, String userSecret)
	{
		User userEntity = getEntityByID(userID);
		NewDeviceRequest newDeviceRequestEntity = userEntity.getNewDeviceRequest();
		if (newDeviceRequestEntity == null)
		{
			throw DeviceRequestException.noDeviceRequestPresent(userID);
		}
		if (isExpired(newDeviceRequestEntity))
		{
			throw DeviceRequestException.deviceRequestExpired(userID);
		}

		if (StringUtils.isBlank(userSecret))
		{
			return NewDeviceRequestDTO.createInstance(userEntity.getNewDeviceRequest());
		}
		else
		{
			newDeviceRequestEntity.decryptUserPassword(userSecret);
			return NewDeviceRequestDTO.createInstanceWithPassword(newDeviceRequestEntity);
		}
	}

	private boolean isExpired(NewDeviceRequest newDeviceRequestEntity)
	{
		Date creationTime = newDeviceRequestEntity.getCreationTime();
		return (creationTime.getTime() + getExpirationIntervalMillis() < System.currentTimeMillis());
	}

	private long getExpirationIntervalMillis()
	{
		return properties.getNewDeviceRequestExpirationDays() * 24 * 60 * 60 * 1000;
	}

	@Transactional
	public void clearNewDeviceRequestForUser(UUID userID)
	{
		User userEntity = getEntityByID(userID);
		NewDeviceRequest existingNewDeviceRequestEntity = userEntity.getNewDeviceRequest();
		if (existingNewDeviceRequestEntity != null)
		{
			userEntity.setNewDeviceRequest(null);
			User.getRepository().save(userEntity);
		}
	}

	/*
	 * Gathers all entities that contain encrypted data from the database.
	 */
	static class EncryptedUserData
	{
		final User userEntity;
		final MessageSource namedMessageSource;
		final MessageSource anonymousMessageSource;

		EncryptedUserData(User userEntity, UserAnonymized userAnonymizedEntity, MessageSource namedMessageSource,
				MessageSource anonymousMessageSource)
		{
			this.userEntity = userEntity;
			this.namedMessageSource = namedMessageSource;
			this.anonymousMessageSource = anonymousMessageSource;
		}

		public void loadLazyEncryptedData()
		{
			// load encrypted data fully, also from lazy relations
			// see architecture overview for which classes contain encrypted data
			// the relation to user private is currently the only lazy relation
			// (this could also be achieved with very complex reflection)
			this.userEntity.loadFully();
		}
	}
}
