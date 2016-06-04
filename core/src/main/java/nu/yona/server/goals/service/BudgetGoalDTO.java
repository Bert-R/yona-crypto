/*******************************************************************************
 * Copyright (c) 2016 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.goals.service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import nu.yona.server.Constants;
import nu.yona.server.goals.entities.ActivityCategory;
import nu.yona.server.goals.entities.BudgetGoal;
import nu.yona.server.goals.entities.Goal;

@JsonRootName("budgetGoal")
public class BudgetGoalDTO extends GoalDTO
{
	private final int maxDurationMinutes;

	@JsonCreator
	public BudgetGoalDTO(
			@JsonFormat(pattern = Constants.ISO_DATE_PATTERN) @JsonProperty("creationTime") Optional<ZonedDateTime> creationTime,
			@JsonProperty(required = true, value = "maxDurationMinutes") int maxDurationMinutes)
	{
		super(creationTime);

		this.maxDurationMinutes = maxDurationMinutes;
	}

	public BudgetGoalDTO(UUID id, UUID activityCategoryID, int maxDurationMinutes, ZonedDateTime creationTime,
			Optional<ZonedDateTime> endTime, boolean mandatory)
	{
		super(id, Optional.of(creationTime), endTime, activityCategoryID, mandatory);

		this.maxDurationMinutes = maxDurationMinutes;
	}

	@Override
	public String getType()
	{
		return "BudgetGoal";
	}

	@Override
	public void validate()
	{
		if (maxDurationMinutes < 0)
		{
			throw GoalServiceException.budgetGoalMaxDurationNegative(maxDurationMinutes);
		}
	}

	@Override
	public boolean isGoalChanged(Goal existingGoal)
	{
		return ((BudgetGoal) existingGoal).getMaxDurationMinutes() != maxDurationMinutes;
	}

	@Override
	public void updateGoalEntity(Goal existingGoal)
	{
		((BudgetGoal) existingGoal).setMaxDurationMinutes(maxDurationMinutes);
	}

	public int getMaxDurationMinutes()
	{
		return maxDurationMinutes;
	}

	public static BudgetGoalDTO createInstance(BudgetGoal entity)
	{
		return new BudgetGoalDTO(entity.getID(), entity.getActivityCategory().getID(), entity.getMaxDurationMinutes(),
				entity.getCreationTime(), Optional.ofNullable(entity.getEndTime()), entity.isMandatory());
	}

	public BudgetGoal createGoalEntity()
	{
		ActivityCategory activityCategory = ActivityCategory.getRepository().findOne(this.getActivityCategoryID());
		if (activityCategory == null)
		{
			throw ActivityCategoryNotFoundException.notFound(this.getActivityCategoryID());
		}
		return BudgetGoal.createInstance(getCreationTime().orElse(ZonedDateTime.now()), activityCategory,
				this.maxDurationMinutes);
	}
}
