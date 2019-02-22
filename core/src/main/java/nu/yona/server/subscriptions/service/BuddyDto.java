/*******************************************************************************
 * Copyright (c) 2015, 2019 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.subscriptions.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import nu.yona.server.Constants;
import nu.yona.server.Translator;
import nu.yona.server.subscriptions.entities.Buddy;
import nu.yona.server.subscriptions.entities.BuddyAnonymized.Status;
import nu.yona.server.util.TimeUtil;

@JsonRootName("buddy")
public class BuddyDto
{
	private static final Logger logger = LoggerFactory.getLogger(BuddyDto.class);

	public static final String USER_REL_NAME = "user";
	public static final String GOALS_REL_NAME = "goals";

	private final UUID id;
	private final UserDto user;
	private final String personalInvitationMessage;
	private final Optional<UUID> userAnonymizedId;
	private final Optional<LocalDate> lastMonitoredActivityDate;
	private final Status sendingStatus;
	private final Status receivingStatus;
	private final LocalDateTime lastStatusChangeTime;

	public BuddyDto(UUID id, UserDto user, Optional<UUID> userAnonymizedId, Optional<LocalDate> lastMonitoredActivityDate,
			Status sendingStatus, Status receivingStatus, LocalDateTime lastStatusChangeTime)
	{
		this(id, user, null, userAnonymizedId, lastMonitoredActivityDate, sendingStatus, receivingStatus, lastStatusChangeTime);
	}

	public BuddyDto(UserDto user, String personalInvitationMessage, Status sendingStatus, Status receivingStatus,
			LocalDateTime lastStatusChangeTime)
	{
		this(null, user, personalInvitationMessage, Optional.empty(), Optional.empty(), sendingStatus, receivingStatus,
				lastStatusChangeTime);
	}

	private BuddyDto(UUID id, UserDto user, String personalInvitationMessage, Optional<UUID> userAnonymizedId,
			Optional<LocalDate> lastMonitoredActivityDate, Status sendingStatus, Status receivingStatus,
			LocalDateTime lastStatusChangeTime)
	{
		this.id = id;
		this.user = Objects.requireNonNull(user);
		this.personalInvitationMessage = personalInvitationMessage;
		this.userAnonymizedId = userAnonymizedId;
		this.lastMonitoredActivityDate = lastMonitoredActivityDate;
		this.sendingStatus = sendingStatus;
		this.receivingStatus = receivingStatus;
		this.lastStatusChangeTime = lastStatusChangeTime;
	}

	@JsonIgnore
	public UUID getId()
	{
		return id;
	}

	@JsonIgnore
	public String getPersonalInvitationMessage()
	{
		return personalInvitationMessage;
	}

	@JsonIgnore
	public UserDto getUser()
	{
		return user;
	}

	Buddy createBuddyEntity()
	{
		return Buddy.createInstance(user.getId(), user.getPrivateData().getFirstName(), user.getPrivateData().getLastName(),
				determineTempNickname(), user.getPrivateData().getUserPhotoId(), getSendingStatus(), getReceivingStatus());
	}

	private String determineTempNickname()
	{
		// Used to till the user accepted the request and shared their nickname
		return Translator.getInstance().getLocalizedMessage("message.temp.nickname", user.getPrivateData().getFirstName(),
				user.getPrivateData().getLastName());
	}

	public static BuddyDto createInstance(Buddy buddyEntity)
	{
		return new BuddyDto(buddyEntity.getId(),
				UserDto.createInstanceWithBuddyData(buddyEntity.getUser(), BuddyUserPrivateDataDto.createInstance(buddyEntity)),
				getBuddyUserAnonymizedId(buddyEntity), getLastMonitoredActivityDate(buddyEntity), buddyEntity.getSendingStatus(),
				buddyEntity.getReceivingStatus(), buddyEntity.getLastStatusChangeTime());
	}

	private static Optional<UUID> getBuddyUserAnonymizedId(Buddy buddyEntity)
	{
		return BuddyService.canIncludePrivateData(buddyEntity) ? buddyEntity.getUserAnonymizedId() : Optional.empty();
	}

	private static Optional<LocalDate> getLastMonitoredActivityDate(Buddy buddyEntity)
	{
		return BuddyService.canIncludePrivateData(buddyEntity)
				? buddyEntity.getBuddyAnonymized().getUserAnonymized().getLastMonitoredActivityDate()
				: Optional.empty();
	}

	@JsonFormat(pattern = Constants.ISO_DATE_PATTERN)
	@JsonInclude(Include.NON_EMPTY)
	public Optional<LocalDate> getLastMonitoredActivityDate()
	{
		return lastMonitoredActivityDate;
	}

	public Status getSendingStatus()
	{
		return sendingStatus;
	}

	public Status getReceivingStatus()
	{
		return receivingStatus;
	}

	@JsonProperty("lastStatusChangeTime")
	@JsonFormat(pattern = Constants.ISO_DATE_TIME_PATTERN)
	public ZonedDateTime getLastStatusChangeTimeAsZonedDateTime()
	{
		if (lastStatusChangeTime == null)
		{
			logger.error("lastStatusChangeTime == null, returning 'now'. Buddy ID: {}", getId());
			return TimeUtil.toUtcZonedDateTime(TimeUtil.utcNow());
		}
		return TimeUtil.toUtcZonedDateTime(lastStatusChangeTime);
	}

	@JsonIgnore
	public LocalDateTime getLastStatusChangeTime()
	{
		return lastStatusChangeTime;
	}

	@JsonIgnore
	public Optional<UUID> getUserAnonymizedId()
	{
		return userAnonymizedId;
	}
}
