/*******************************************************************************
 * Copyright (c) 2016, 2018 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.analysis.service;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anySetOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import nu.yona.server.Translator;
import nu.yona.server.analysis.entities.Activity;
import nu.yona.server.analysis.entities.ActivityRepository;
import nu.yona.server.analysis.entities.DayActivity;
import nu.yona.server.analysis.entities.DayActivityRepository;
import nu.yona.server.analysis.entities.WeekActivity;
import nu.yona.server.analysis.entities.WeekActivityRepository;
import nu.yona.server.analysis.service.AnalysisEngineService.ActivityPayload;
import nu.yona.server.crypto.pubkey.PublicKeyUtil;
import nu.yona.server.device.entities.DeviceAnonymized;
import nu.yona.server.device.entities.DeviceAnonymized.OperatingSystem;
import nu.yona.server.device.entities.DeviceAnonymizedRepository;
import nu.yona.server.device.service.DeviceAnonymizedDto;
import nu.yona.server.device.service.DeviceService;
import nu.yona.server.goals.entities.ActivityCategory;
import nu.yona.server.goals.entities.BudgetGoal;
import nu.yona.server.goals.entities.Goal;
import nu.yona.server.goals.entities.TimeZoneGoal;
import nu.yona.server.goals.service.ActivityCategoryDto;
import nu.yona.server.goals.service.ActivityCategoryService;
import nu.yona.server.goals.service.GoalDto;
import nu.yona.server.goals.service.GoalService;
import nu.yona.server.messaging.entities.MessageDestination;
import nu.yona.server.messaging.entities.MessageRepository;
import nu.yona.server.messaging.service.MessageService;
import nu.yona.server.properties.AnalysisServiceProperties;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.subscriptions.entities.UserAnonymized;
import nu.yona.server.subscriptions.service.UserAnonymizedDto;
import nu.yona.server.subscriptions.service.UserAnonymizedService;
import nu.yona.server.test.util.JUnitUtil;
import nu.yona.server.util.LockPool;
import nu.yona.server.util.TimeUtil;
import nu.yona.server.util.TransactionHelper;

@RunWith(MockitoJUnitRunner.class)
public class AnalysisEngineServiceTest
{
	private final Map<String, Goal> goalMap = new HashMap<>();

	@Mock
	private ActivityCategoryService mockActivityCategoryService;
	@Mock
	private ActivityCategoryService.FilterService mockActivityCategoryFilterService;
	@Mock
	private UserAnonymizedService mockUserAnonymizedService;
	@Mock
	private GoalService mockGoalService;
	@Mock
	private MessageService mockMessageService;
	@Mock
	private YonaProperties mockYonaProperties;
	@Mock
	private ActivityCacheService mockAnalysisEngineCacheService;
	@Mock
	private MessageRepository mockMessageRepository;
	@Mock
	private ActivityRepository mockActivityRepository;
	@Mock
	private DayActivityRepository mockDayActivityRepository;
	@Mock
	private WeekActivityRepository mockWeekActivityRepository;
	@Mock
	private LockPool<UUID> userAnonymizedSynchronizer;
	@Mock
	private TransactionHelper transactionHelper;
	@Mock
	private DeviceService mockDeviceService;
	@Mock
	private DeviceAnonymizedRepository mockDeviceAnonymizedRepository;
	@Mock
	private Appender<ILoggingEvent> mockLogAppender;
	@Mock
	private ActivityUpdateService mockActivityUpdater;

	@InjectMocks
	private final AnalysisEngineService service = new AnalysisEngineService();

	private Goal gamblingGoal;
	private Goal newsGoal;
	private Goal gamingGoal;
	private Goal socialGoal;
	private Goal shoppingGoal;
	private UUID userAnonId;
	private UUID deviceAnonId;
	private DeviceAnonymized deviceAnonEntity;
	private UserAnonymized userAnonEntity;
	private UserAnonymizedDto userAnonDto;

	private ZoneId userAnonZoneId;

	private DeviceAnonymizedDto deviceAnonDto;

	@Before
	public void setUp()
	{
		Logger logger = (Logger) LoggerFactory.getLogger(AnalysisEngineService.class);
		logger.addAppender(mockLogAppender);

		LocalDateTime yesterday = TimeUtil.utcNow().minusDays(1);
		gamblingGoal = BudgetGoal.createNoGoInstance(yesterday,
				ActivityCategory.createInstance(UUID.randomUUID(), usString("gambling"), false,
						new HashSet<>(Arrays.asList("poker", "lotto")), new HashSet<>(Arrays.asList("Poker App", "Lotto App")),
						usString("Descr")));
		newsGoal = BudgetGoal.createNoGoInstance(yesterday, ActivityCategory.createInstance(UUID.randomUUID(), usString("news"),
				false, new HashSet<>(Arrays.asList("refdag", "bbc")), Collections.emptySet(), usString("Descr")));
		gamingGoal = BudgetGoal.createNoGoInstance(yesterday, ActivityCategory.createInstance(UUID.randomUUID(),
				usString("gaming"), false, new HashSet<>(Arrays.asList("games")), Collections.emptySet(), usString("Descr")));
		socialGoal = TimeZoneGoal.createInstance(yesterday,
				ActivityCategory.createInstance(UUID.randomUUID(), usString("social"), false,
						new HashSet<>(Arrays.asList("social")), Collections.emptySet(), usString("Descr")),
				Collections.emptyList());
		shoppingGoal = BudgetGoal.createInstance(yesterday, ActivityCategory.createInstance(UUID.randomUUID(),
				usString("shopping"), false, new HashSet<>(Arrays.asList("webshop")), Collections.emptySet(), usString("Descr")),
				1);

		goalMap.put("gambling", gamblingGoal);
		goalMap.put("news", newsGoal);
		goalMap.put("gaming", gamingGoal);
		goalMap.put("social", socialGoal);
		goalMap.put("shopping", shoppingGoal);

		when(mockYonaProperties.getAnalysisService()).thenReturn(new AnalysisServiceProperties());

		when(mockActivityCategoryService.getAllActivityCategories()).thenReturn(getAllActivityCategories());
		when(mockActivityCategoryFilterService.getMatchingCategoriesForSmoothwallCategories(anySetOf(String.class)))
				.thenAnswer(new Answer<Set<ActivityCategoryDto>>() {
					@Override
					public Set<ActivityCategoryDto> answer(InvocationOnMock invocation) throws Throwable
					{
						Object[] args = invocation.getArguments();
						@SuppressWarnings("unchecked")
						Set<String> smoothwallCategories = (Set<String>) args[0];
						return getAllActivityCategories()
								.stream().filter(ac -> ac.getSmoothwallCategories().stream()
										.filter(smoothwallCategories::contains).findAny().isPresent())
								.collect(Collectors.toSet());
					}
				});
		when(mockActivityCategoryFilterService.getMatchingCategoriesForApp(any(String.class)))
				.thenAnswer(new Answer<Set<ActivityCategoryDto>>() {
					@Override
					public Set<ActivityCategoryDto> answer(InvocationOnMock invocation) throws Throwable
					{
						Object[] args = invocation.getArguments();
						String application = (String) args[0];
						return getAllActivityCategories().stream().filter(ac -> ac.getApplications().contains(application))
								.collect(Collectors.toSet());
					}
				});

		// Set up UserAnonymized instance.
		MessageDestination anonMessageDestinationEntity = MessageDestination
				.createInstance(PublicKeyUtil.generateKeyPair().getPublic());
		Set<Goal> goals = new HashSet<>(Arrays.asList(gamblingGoal, gamingGoal, socialGoal, shoppingGoal));
		deviceAnonEntity = DeviceAnonymized.createInstance(0, OperatingSystem.IOS, "Unknown");
		deviceAnonId = deviceAnonEntity.getId();
		userAnonEntity = UserAnonymized.createInstance(anonMessageDestinationEntity, goals);
		userAnonEntity.addDeviceAnonymized(deviceAnonEntity);
		userAnonDto = UserAnonymizedDto.createInstance(userAnonEntity);
		deviceAnonDto = DeviceAnonymizedDto.createInstance(deviceAnonEntity);
		userAnonId = userAnonDto.getId();
		userAnonZoneId = userAnonDto.getTimeZone();

		// Stub the UserAnonymizedService to return our user.
		when(mockUserAnonymizedService.getUserAnonymized(userAnonId)).thenReturn(userAnonDto);
		when(mockUserAnonymizedService.getUserAnonymizedEntity(userAnonId)).thenReturn(userAnonEntity);

		// Stub the GoalService to return our goals.
		when(mockGoalService.getGoalEntityForUserAnonymizedId(userAnonId, gamblingGoal.getId())).thenReturn(gamblingGoal);
		when(mockGoalService.getGoalEntityForUserAnonymizedId(userAnonId, gamingGoal.getId())).thenReturn(gamingGoal);
		when(mockGoalService.getGoalEntityForUserAnonymizedId(userAnonId, socialGoal.getId())).thenReturn(socialGoal);
		when(mockGoalService.getGoalEntityForUserAnonymizedId(userAnonId, shoppingGoal.getId())).thenReturn(shoppingGoal);

		// Mock the transaction helper
		doAnswer(new Answer<Void>() {
			@Override
			public Void answer(InvocationOnMock invocation) throws Throwable
			{
				invocation.getArgumentAt(0, Runnable.class).run();
				return null;
			}
		}).when(transactionHelper).executeInNewTransaction(any(Runnable.class));

		// Mock the week activity repository
		when(mockWeekActivityRepository.findOne(any(UUID.class), any(UUID.class), any(LocalDate.class)))
				.thenAnswer(new Answer<WeekActivity>() {
					@Override
					public WeekActivity answer(InvocationOnMock invocation) throws Throwable
					{
						Optional<Goal> goal = goalMap.values().stream()
								.filter(g -> g.getId() == invocation.getArgumentAt(1, UUID.class)).findAny();
						if (!goal.isPresent())
						{
							return null;
						}
						return goal.get().getWeekActivities().stream()
								.filter(wa -> wa.getStartDate().equals(invocation.getArgumentAt(2, LocalDate.class))).findAny()
								.orElse(null);
					}
				});

		// Mock device service and repo
		when(mockDeviceService.getDeviceAnonymized(userAnonDto, -1)).thenReturn(deviceAnonDto);
		when(mockDeviceService.getDeviceAnonymized(userAnonDto, deviceAnonId)).thenReturn(deviceAnonDto);
		when(mockDeviceAnonymizedRepository.getOne(deviceAnonId)).thenReturn(deviceAnonEntity);

		// Set up the repository mocks
		JUnitUtil.setUpRepositoryMock(mockMessageRepository);
		JUnitUtil.setUpRepositoryMock(mockActivityRepository);
		JUnitUtil.setUpRepositoryMock(mockDayActivityRepository);
	}

	private Map<Locale, String> usString(String string)
	{
		return Collections.singletonMap(Translator.EN_US_LOCALE, string);
	}

	private Set<ActivityCategoryDto> getAllActivityCategories()
	{
		return goalMap.values().stream().map(goal -> ActivityCategoryDto.createInstance(goal.getActivityCategory()))
				.collect(Collectors.toSet());
	}

	@Test
	public void getRelevantSmoothwallCategories_default_containsExpectedItems()
	{
		Set<String> result = service.getRelevantSmoothwallCategories();

		assertThat(result, containsInAnyOrder("poker", "lotto", "refdag", "bbc", "games", "social", "webshop"));
	}

	@Test
	public void analyze_secondConflictAfterConflictInterval_addActivity()
	{
		// Normally there is one conflict message sent.
		// Set a short conflict interval such that the conflict messages are not aggregated.
		AnalysisServiceProperties p = new AnalysisServiceProperties();
		p.setUpdateSkipWindow("PT0.001S");
		p.setConflictInterval("PT0.01S");
		when(mockYonaProperties.getAnalysisService()).thenReturn(p);

		mockExistingActivity(gamblingGoal, now());

		// Execute the analysis engine service after a period of inactivity longer than the conflict interval.

		try
		{
			Thread.sleep(11L);
		}
		catch (InterruptedException e)
		{

		}

		service.analyze(userAnonId, createNetworkActivityForCategories("lotto"));

		verifyAddActivity(gamblingGoal);
	}

	@Test
	public void analyze_secondConflictWithinConflictInterval_noUpdate()
	{
		mockExistingActivity(gamblingGoal, now());

		service.analyze(userAnonId, createNetworkActivityForCategories("lotto"));

		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)),
				any());
	}

	@Test
	public void analyze_matchingCategory_addActivity()
	{
		service.analyze(userAnonId, createNetworkActivityForCategories("lotto"));

		verifyAddActivity(gamblingGoal);
	}

	@Test
	public void analyze_matchOneCategoryOfMultiple_oneAddActivity()
	{
		service.analyze(userAnonId, createNetworkActivityForCategories("refdag", "lotto"));

		verifyAddActivity(gamblingGoal);
	}

	@Test
	public void analyze_multipleMatchingCategories_multipleAddActivity()
	{
		service.analyze(userAnonId, createNetworkActivityForCategories("lotto", "games"));

		verifyAddActivity(gamblingGoal, gamingGoal);
	}

	@Test
	public void analyze_multipleFollowingCallsWithinConflictInterval_updateTimeLastActivity()
	{
		// Normally there is one conflict message sent.
		// Set update skip window to 0 such that the conflict messages are aggregated immediately.
		AnalysisServiceProperties p = new AnalysisServiceProperties();
		p.setUpdateSkipWindow("PT0S");
		when(mockYonaProperties.getAnalysisService()).thenReturn(p);

		ZonedDateTime timeOfMockedExistingActivity = now();
		mockExistingActivity(gamblingGoal, timeOfMockedExistingActivity);

		service.analyze(userAnonId, createNetworkActivityForCategories("refdag")); // not matching category
		service.analyze(userAnonId, createNetworkActivityForCategories("lotto")); // matching category
		service.analyze(userAnonId, createNetworkActivityForCategories("poker")); // matching category
		service.analyze(userAnonId, createNetworkActivityForCategories("refdag")); // not matching category
		service.analyze(userAnonId, createNetworkActivityForCategories("poker")); // matching category

		// Verify that the cache is used to check existing activity
		verify(mockAnalysisEngineCacheService, times(3)).fetchLastActivityForUser(userAnonId, deviceAnonId, gamblingGoal.getId());

		verify(mockActivityUpdater, times(3)).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)),
				any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_noMatchingCategory_noAddOrUpdateActivity()
	{
		service.analyze(userAnonId, createNetworkActivityForCategories("refdag"));

		verify(mockAnalysisEngineCacheService, never()).fetchLastActivityForUser(eq(userAnonId), eq(deviceAnonId), any());

		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityOnNewDay_addActivity()
	{
		ZonedDateTime today = now().truncatedTo(ChronoUnit.DAYS);
		// mock earlier activity at yesterday 23:59:58,
		// add new activity at today 00:00:01
		ZonedDateTime existingActivityTime = today.minusDays(1).withHour(23).withMinute(59).withSecond(58);

		mockExistingActivity(gamblingGoal, existingActivityTime);

		ZonedDateTime startTime = today.withHour(0).withMinute(0).withSecond(1);
		ZonedDateTime endTime = today.withHour(0).withMinute(10);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityCompletelyPrecedingLastCachedActivity_addActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityStartTime = now.minusMinutes(4);
		ZonedDateTime existingActivityEndTime = existingActivityStartTime.plusMinutes(2);

		mockExistingActivity(gamblingGoal, existingActivityStartTime, existingActivityEndTime, "Poker App");

		ZonedDateTime startTime = now.minusMinutes(10);
		ZonedDateTime endTime = startTime.plusMinutes(5);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityCompletelyPrecedingLastCachedActivityOverlappingExistingActivity_updateTimeExistingActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityTimeStartTime = now.minusMinutes(20);
		ZonedDateTime existingActivityTimeEndTime = existingActivityTimeStartTime.plusMinutes(10);

		Activity existingActivityOne = createActivity(existingActivityTimeStartTime, existingActivityTimeEndTime, "Poker App");
		Activity existingActivityTwo = createActivity(now, now, "Poker App");
		mockExistingActivities(gamblingGoal, existingActivityOne, existingActivityTwo);

		when(mockActivityRepository.findOverlappingOfSameApp(any(DayActivity.class), any(UUID.class), any(UUID.class),
				any(String.class), any(LocalDateTime.class), any(LocalDateTime.class))).thenAnswer(new Answer<List<Activity>>() {
					@Override
					public List<Activity> answer(InvocationOnMock invocation) throws Throwable
					{
						return Arrays.asList(existingActivityOne);
					}
				});

		// Test an activity
		ZonedDateTime startTime = existingActivityTimeStartTime.plusMinutes(5);
		ZonedDateTime endTime = startTime.plusMinutes(7);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		// Verify that a database lookup was done finding the existing DayActivity to update
		verify(mockDayActivityRepository).findOne(userAnonId, now.toLocalDate(), gamblingGoal.getId());

		verify(mockActivityUpdater).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_networkActivityCompletelyPrecedingLastCachedActivityOverlappingMultipleExistingActivities_updateTimeExistingActivityOnFirstActivityAndLogsWarning()
	{
		ZonedDateTime now = now();
		DayActivity existingDayActivity = mockExistingActivities(gamblingGoal,
				createActivity(now.minusMinutes(10), now.minusMinutes(8)), createActivity(now.minusMinutes(1), now));
		when(mockActivityRepository.findOverlappingOfSameApp(any(DayActivity.class), any(UUID.class), any(UUID.class),
				any(String.class), any(LocalDateTime.class), any(LocalDateTime.class))).thenAnswer(new Answer<List<Activity>>() {
					@Override
					public List<Activity> answer(InvocationOnMock invocation) throws Throwable
					{
						return existingDayActivity.getActivities().stream().collect(Collectors.toList());
					}
				});
		String expectedWarnMessage = MessageFormat.format(
				"Multiple overlapping network activities found. The payload has start time {0} and end time {1}. The day activity ID is {2} and the activity category ID is {3}. The overlapping activities are: {4}, {5}.",
				now.minusMinutes(9).toLocalDateTime(), now.minusMinutes(9).toLocalDateTime(), existingDayActivity.getId(),
				gamblingGoal.getActivityCategory().getId(), existingDayActivity.getActivities().get(0),
				existingDayActivity.getActivities().get(1));

		service.analyze(userAnonId, createNetworkActivityForCategories(now.minusMinutes(9), "poker"));

		verify(mockActivityUpdater).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());

		List<Activity> activities = existingDayActivity.getActivities();
		assertThat(activities.size(), equalTo(2));
		assertThat(activities.get(0).getApp(), equalTo(Optional.empty()));
		assertThat(activities.get(0).getStartTimeAsZonedDateTime(), equalTo(now.minusMinutes(10)));
		assertThat(activities.get(0).getEndTimeAsZonedDateTime(), equalTo(now.minusMinutes(8)));
		assertThat(activities.get(1).getApp(), equalTo(Optional.empty()));
		assertThat(activities.get(1).getStartTimeAsZonedDateTime(), equalTo(now.minusMinutes(1)));
		assertThat(activities.get(1).getEndTimeAsZonedDateTime(), equalTo(now));

		ArgumentCaptor<ILoggingEvent> logEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
		verify(mockLogAppender).doAppend(logEventCaptor.capture());
		assertThat(logEventCaptor.getValue().getLevel(), equalTo(Level.WARN));
		assertThat(logEventCaptor.getValue().getFormattedMessage(), equalTo(expectedWarnMessage));
	}

	@Test
	public void analyze_appActivityCompletelyPrecedingLastCachedActivityOverlappingMultipleExistingActivities_updateTimeExistingActivityOnFirstActivityAndLogsWarning()
	{
		ZonedDateTime now = now();
		DayActivity existingDayActivity = mockExistingActivities(gamblingGoal,
				createActivity(now.minusMinutes(10), now.minusMinutes(8), "Lotto App"),
				createActivity(now.minusMinutes(7), now.minusMinutes(5), "Lotto App"), createActivity(now, now, "Lotto App"));
		when(mockActivityRepository.findOverlappingOfSameApp(any(DayActivity.class), any(UUID.class), any(UUID.class),
				any(String.class), any(LocalDateTime.class), any(LocalDateTime.class))).thenAnswer(new Answer<List<Activity>>() {
					@Override
					public List<Activity> answer(InvocationOnMock invocation) throws Throwable
					{
						return existingDayActivity.getActivities().stream().collect(Collectors.toList());
					}
				});
		String expectedWarnMessage = MessageFormat.format(
				"Multiple overlapping app activities of ''Lotto App'' found. The payload has start time {0} and end time {1}. The day activity ID is {2} and the activity category ID is {3}. The overlapping activities are: {4}, {5}, {6}.",
				now.minusMinutes(9).toLocalDateTime(), now.minusMinutes(2).toLocalDateTime(), existingDayActivity.getId(),
				gamblingGoal.getActivityCategory().getId(), existingDayActivity.getActivities().get(0),
				existingDayActivity.getActivities().get(1), existingDayActivity.getActivities().get(2));

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Lotto App", now.minusMinutes(9), now.minusMinutes(2)));

		verify(mockActivityUpdater).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());

		ArgumentCaptor<ILoggingEvent> logEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
		verify(mockLogAppender).doAppend(logEventCaptor.capture());
		assertThat(logEventCaptor.getValue().getLevel(), equalTo(Level.WARN));
		assertThat(logEventCaptor.getValue().getFormattedMessage(), equalTo(expectedWarnMessage));
	}

	@Test
	public void analyze_appActivityOverlappingLastCachedActivityBeginAndEnd_updateTimeLastActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityStartTime = now.minusMinutes(5);
		ZonedDateTime existingActivityEndTime = now.minusSeconds(15);

		mockExistingActivity(gamblingGoal, existingActivityStartTime, existingActivityEndTime, "Poker App");

		ZonedDateTime startTime = now.minusMinutes(10);
		ZonedDateTime endTime = now;

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		verify(mockActivityUpdater).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityOverlappingLastCachedActivityBeginOnly_updateTimeLastActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityStartTime = now.minusMinutes(5);
		ZonedDateTime existingActivityEndTime = now.minusSeconds(15);

		mockExistingActivity(gamblingGoal, existingActivityStartTime, existingActivityEndTime, "Poker App");

		ZonedDateTime startTime = now.minusMinutes(10);
		ZonedDateTime endTime = existingActivityEndTime.minusMinutes(2);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		verify(mockActivityUpdater).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityPreviousDayPrecedingCachedDayActivity_addActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime yesterdayTime = now.minusDays(1);

		mockExistingActivity(gamblingGoal, now);

		ZonedDateTime startTime = yesterdayTime;
		ZonedDateTime endTime = yesterdayTime.plusMinutes(10);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		// Verify that a database lookup was done for yesterday
		verify(mockDayActivityRepository).findOne(userAnonId, yesterdayTime.toLocalDate(), gamblingGoal.getId());

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_crossDayAppActivity_twoDayActivitiesCreated()
	{
		ZonedDateTime endTime = now();
		ZonedDateTime startTime = endTime.minusDays(1);

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Poker App", startTime, endTime));

		verify(mockActivityUpdater)
				.addActivity(any(),
						eq(ActivityPayload.createInstance(userAnonDto, deviceAnonDto, startTime,
								endTime.truncatedTo(ChronoUnit.DAYS), "Poker App")),
						eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater).addActivity(any(), eq(ActivityPayload.createInstance(userAnonDto, deviceAnonDto,
				endTime.truncatedTo(ChronoUnit.DAYS), endTime, "Poker App")), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityAfterNetworkActivityWithinConflictInterval_addActivity()
	{
		ZonedDateTime now = now();
		mockExistingActivity(gamblingGoal, now());

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Lotto App", now.minusMinutes(4), now.minusMinutes(2)));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_networkActivityAfterAppActivityWithinConflictInterval_addActivity()
	{
		ZonedDateTime now = now();
		mockExistingActivity(gamblingGoal, now.minusMinutes(10), now.minusMinutes(5), "Lotto App");

		service.analyze(userAnonId, createNetworkActivityForCategories("lotto"));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivityDifferentAppWithinConflictInterval_addActivity()
	{
		ZonedDateTime now = now();
		mockExistingActivity(gamblingGoal, now.minusMinutes(10), now.minusMinutes(5), "Poker App");

		service.analyze(userAnonId, deviceAnonId, createSingleAppActivity("Lotto App", now.minusMinutes(4), now.minusMinutes(2)));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).updateTimeLastActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivitySameAppWithinConflictIntervalContinuous_updateTimeLastActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityEndTime = now.minusMinutes(5);
		mockExistingActivity(gamblingGoal, now.minusMinutes(10), existingActivityEndTime, "Lotto App");

		service.analyze(userAnonId, deviceAnonId,
				createSingleAppActivity("Lotto App", existingActivityEndTime, now.minusMinutes(2)));

		verify(mockActivityUpdater).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivitySameAppOverlappingLastCachedActivityEndTime_updateTimeLastActivity()
	{
		ZonedDateTime now = now();
		mockExistingActivity(gamblingGoal, now.minusMinutes(10), now.minusMinutes(5), "Lotto App");

		service.analyze(userAnonId, deviceAnonId,
				createSingleAppActivity("Lotto App", now.minusMinutes(5).minusSeconds(30), now.minusMinutes(2)));

		verify(mockActivityUpdater).updateTimeLastActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
		verify(mockActivityUpdater, never()).updateTimeExistingActivity(any(), any(), any());
		verify(mockActivityUpdater, never()).addActivity(any(), any(), any(), any());
	}

	@Test
	public void analyze_appActivitySameAppWithinConflictIntervalButNotContinuous_addActivity()
	{
		ZonedDateTime now = now();
		ZonedDateTime existingActivityEndTime = now.minusMinutes(5);
		mockExistingActivity(gamblingGoal, now.minusMinutes(10), now.minusMinutes(5), "Lotto App");

		service.analyze(userAnonId, deviceAnonId,
				createSingleAppActivity("Lotto App", existingActivityEndTime.plusSeconds(1), now.minusMinutes(2)));

		verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(gamblingGoal)), any());
	}

	private NetworkActivityDto createNetworkActivityForCategories(String... conflictCategories)
	{
		return new NetworkActivityDto(-1, new HashSet<>(Arrays.asList(conflictCategories)),
				"http://localhost/test" + new Random().nextInt(), Optional.empty());
	}

	private NetworkActivityDto createNetworkActivityForCategories(ZonedDateTime time, String... conflictCategories)
	{
		return new NetworkActivityDto(-1, new HashSet<>(Arrays.asList(conflictCategories)),
				"http://localhost/test" + new Random().nextInt(), Optional.of(time));
	}

	private void verifyAddActivity(Goal... forGoals)
	{
		for (Goal forGoal : forGoals)
		{
			verify(mockActivityUpdater).addActivity(any(), any(), eq(GoalDto.createInstance(forGoal)), any());
		}
	}

	private DayActivity mockExistingActivity(Goal forGoal, ZonedDateTime activityTime)
	{
		return mockExistingActivities(forGoal, createActivity(activityTime, activityTime));
	}

	private DayActivity mockExistingActivity(Goal forGoal, ZonedDateTime startTime, ZonedDateTime endTime, String app)
	{
		return mockExistingActivities(forGoal, createActivity(startTime, endTime, app));
	}

	private DayActivity mockExistingActivities(Goal forGoal, Activity... activities)
	{
		LocalDateTime startTime = activities[0].getStartTime();
		DayActivity dayActivity = DayActivity.createInstance(userAnonEntity, forGoal, userAnonZoneId,
				startTime.truncatedTo(ChronoUnit.DAYS).toLocalDate());
		Arrays.asList(activities).forEach(a -> dayActivity.addActivity(a));
		ActivityDto existingActivity = ActivityDto.createInstance(activities[activities.length - 1]);
		when(mockDayActivityRepository.findOne(userAnonId, dayActivity.getStartDate(), forGoal.getId())).thenReturn(dayActivity);
		when(mockAnalysisEngineCacheService.fetchLastActivityForUser(userAnonId, deviceAnonId, forGoal.getId()))
				.thenReturn(existingActivity);
		WeekActivity weekActivity = WeekActivity.createInstance(userAnonEntity, forGoal, userAnonZoneId,
				TimeUtil.getStartOfWeek(startTime.toLocalDate()));
		weekActivity.addDayActivity(dayActivity);
		forGoal.addWeekActivity(weekActivity);
		return dayActivity;
	}

	private Activity createActivity(ZonedDateTime startTime, ZonedDateTime endTime)
	{
		return Activity.createInstance(deviceAnonEntity, userAnonZoneId, startTime.toLocalDateTime(), endTime.toLocalDateTime(),
				Optional.empty());
	}

	private Activity createActivity(ZonedDateTime startTime, ZonedDateTime endTime, String app)
	{
		return Activity.createInstance(deviceAnonEntity, userAnonZoneId, startTime.toLocalDateTime(), endTime.toLocalDateTime(),
				Optional.of(app));
	}

	private AppActivityDto createSingleAppActivity(String app, ZonedDateTime startTime, ZonedDateTime endTime)
	{
		AppActivityDto.Activity[] activities = { new AppActivityDto.Activity(app, startTime, endTime) };
		return new AppActivityDto(now(), activities);
	}

	private ZonedDateTime now()
	{
		return ZonedDateTime.now().withZoneSameInstant(userAnonZoneId);
	}
}